package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.queue.QueueNames
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculationJobServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort

    @Mock lateinit var eventAppender: DomainEventAppender

    @Mock lateinit var pgmqClient: PgmqClient

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
            pgmqClient = pgmqClient,
            ocidResolveTopic = ocidResolveTopic,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
            snapshotRepository = snapshotRepository,
        )
    }

    @Test
    fun `retryExternalApiJob increments OCID retry when job is resolving OCID`() {
        val job = job(status = CalculationJobStatus.OCID_RESOLVING)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId, "OCID_RESOLVE_ERROR")

        assertThat(result).isTrue()
        verify(jobPort).incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")
        verify(jobPort, never()).incrementRetry(eq(job.jobId), any())
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob increments API retry when job is API requested`() {
        val job = job(status = CalculationJobStatus.API_REQUESTED, ocid = "ocid-1")
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetry(job.jobId, "EXTERNAL_API_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isTrue()
        verify(jobPort).incrementRetry(job.jobId, "EXTERNAL_API_ERROR")
        verify(jobPort, never()).incrementRetryForOcid(eq(job.jobId), any())
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob stores provided API error code`() {
        val job = job(status = CalculationJobStatus.API_REQUESTED, ocid = "ocid-1")
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetry(job.jobId, "NEXON_RATE_LIMITED")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId, "NEXON_RATE_LIMITED")

        assertThat(result).isTrue()
        verify(jobPort).incrementRetry(job.jobId, "NEXON_RATE_LIMITED")
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob increments API retry when job is retrying`() {
        val job = job(status = CalculationJobStatus.RETRYING, ocid = "ocid-1", retryCount = 1)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetry(job.jobId, "EXTERNAL_API_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isTrue()
        verify(jobPort).incrementRetry(job.jobId, "EXTERNAL_API_ERROR")
        verify(jobPort, never()).incrementRetryForOcid(eq(job.jobId), any())
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob marks exhausted job failed and archives current message`() {
        val job = job(status = CalculationJobStatus.API_REQUESTED, retryCount = 3, maxRetries = 3)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isTrue()
        verify(jobPort).markFailed(job.jobId, "EXTERNAL_API_ERROR", "Max retries exceeded")
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    @Test
    fun `retryExternalApiJob returns false for non external API processable job`() {
        val job = job(status = CalculationJobStatus.COMPLETED)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isFalse()
        verify(jobPort, never()).incrementRetry(eq(job.jobId), any())
        verify(jobPort, never()).incrementRetryForOcid(eq(job.jobId), any())
        verify(jobPort, never()).markFailed(eq(job.jobId), any(), any())
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    private fun job(
        status: CalculationJobStatus,
        ocid: String? = null,
        retryCount: Int = 0,
        maxRetries: Int = 3,
    ) = CalculationJob(
        jobId = UUID.randomUUID(),
        ocid = ocid,
        userIgn = "test-character",
        presetNo = 1,
        status = status,
        retryCount = retryCount,
        maxRetries = maxRetries,
    )
}
