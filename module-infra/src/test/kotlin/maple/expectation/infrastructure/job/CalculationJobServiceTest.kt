package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculationJobServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort

    @Mock lateinit var eventAppender: DomainEventAppender

    @Mock lateinit var dispatchService: CalculationDispatchService

    @Mock lateinit var ocidResolveTopic: OcidResolveTopic

    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic

    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository

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
            dispatchService = dispatchService,
        )
    }

    @Test
    fun `retryExternalApiJob delegates to dispatchService`() {
        val jobId = UUID.randomUUID()
        whenever(dispatchService.retryExternalApiJob(jobId, "TEST_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(jobId, "TEST_ERROR")

        assertThat(result).isTrue()
        verify(dispatchService).retryExternalApiJob(jobId, "TEST_ERROR")
    }
}
