package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.NexonApiRequestEventFactory
import maple.expectation.infrastructure.mq.event.NexonApiResponseEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ApiDataFetchOrchestratorTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository
    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic
    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    private lateinit var service: ApiDataFetchOrchestrator

    @BeforeEach
    fun setUp() {
        service = ApiDataFetchOrchestrator(
            jobPort = jobPort,
            eventAppender = eventAppender,
            snapshotRepository = snapshotRepository,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
        )
    }

    private fun job(ocid: String? = "ocid-1", retryCount: Int = 0, maxRetries: Int = 5) = CalculationJob(
        jobId = UUID.randomUUID(), ocid = ocid, userIgn = "ign", presetNo = 1,
        status = CalculationJobStatus.API_REQUESTED, retryCount = retryCount, maxRetries = maxRetries,
    )

    @Test
    fun `resolveOcidAndEnqueueApiData enqueues API request on success`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(true)
        whenever(jobPort.findJobById(jobId)).thenReturn(job())

        val result = service.resolveOcidAndEnqueueApiData(jobId, "ocid-1")

        assertThat(result).isTrue()
        verify(eventAppender).append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(jobId.toString(), "ocid-1", "ign", 1))
    }

    @Test
    fun `resolveOcidAndEnqueueApiData returns false when transition fails`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(false)

        val result = service.resolveOcidAndEnqueueApiData(jobId, "ocid-1")

        assertThat(result).isFalse()
        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `saveSnapshotAndMarkReady persists snapshot and enqueues response`() {
        val jobId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val entity = CalculationSnapshotEntity(
            snapshotId = snapshotId,
            jobId = jobId,
            objectKey = "obj/key",
            expiresAt = java.time.Instant.now().plusSeconds(3600),
        )
        whenever(jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(true)
        whenever(jobPort.findJobById(jobId)).thenReturn(job())

        val result = service.saveSnapshotAndMarkReady(entity, jobId, "obj/key")

        assertThat(result).isTrue()
        verify(snapshotRepository).save(entity)
        verify(eventAppender).append(nexonApiResponseTopic, NexonApiResponseEventFactory.create(jobId.toString(), snapshotId.toString(), "obj/key", "ocid-1", "ign", 1))
    }

    @Test
    fun `handleApiFailure marks failed when max retries exceeded`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.findJobById(jobId)).thenReturn(job(retryCount = 5, maxRetries = 5))

        service.handleApiFailure(jobId, "CODE", "boom")

        verify(jobPort).markFailed(jobId, "CODE", "boom")
        verify(eventAppender, never()).append(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `handleApiFailure re-enqueues API request on retry`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.findJobById(jobId)).thenReturn(job(retryCount = 0, maxRetries = 5))
        whenever(jobPort.incrementRetry(jobId, "CODE")).thenReturn(true)

        service.handleApiFailure(jobId, "CODE", "boom")

        verify(eventAppender).append(nexonApiRequestTopic, NexonApiRequestEventFactory.create(jobId.toString(), "ocid-1", "ign", 1, eventType = "RETRY_FETCH"))
    }
}
