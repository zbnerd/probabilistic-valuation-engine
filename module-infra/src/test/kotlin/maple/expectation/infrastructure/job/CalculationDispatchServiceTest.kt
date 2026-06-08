package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.CalculationRequestedPayload
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.queue.QueueNames
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
class CalculationDispatchServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var pgmqClient: PgmqClient
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository

    private lateinit var service: CalculationDispatchService

    @BeforeEach
    fun setUp() {
        service = CalculationDispatchService(jobPort, pgmqClient, snapshotRepository)
    }

    @Test
    fun `retryOcidResolvingJob sends external API payload when retry increments`() {
        val job = job()
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_TIMEOUT")).thenReturn(true)

        val result = service.retryOcidResolvingJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isTrue()
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryOcidResolvingJob does not send when retry fails`() {
        val job = job()
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_TIMEOUT")).thenReturn(false)

        val result = service.retryOcidResolvingJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isFalse()
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    @Test
    fun `retryApiRequestedJob sends external API payload when retry increments`() {
        val job = job()
        whenever(jobPort.incrementRetry(job.jobId, "EXTERNAL_API_TIMEOUT")).thenReturn(true)

        val result = service.retryApiRequestedJob(job.jobId, job.userIgn, job.presetNo)

        assertThat(result).isTrue()
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `dispatchToExternalApi sends payload when transition succeeds`() {
        val job = job()
        whenever(jobPort.transitionStatus(job.jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING)).thenReturn(true)

        service.dispatchToExternalApi(job.jobId, job.userIgn, job.presetNo)

        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `dispatchToExternalApi does not send when transition fails`() {
        val job = job()
        whenever(jobPort.transitionStatus(job.jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING)).thenReturn(false)

        service.dispatchToExternalApi(job.jobId, job.userIgn, job.presetNo)

        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    @Test
    fun `dispatchCalculationCompleted sends to CALCULATION_COMPLETED queue`() {
        val payload = makeCompletedPayload(jobId = "job-1")

        service.dispatchCalculationCompleted(payload)

        verify(pgmqClient).send(QueueNames.CALCULATION_COMPLETED, payload)
    }

    @Test
    fun `saveInputSnapshotAndDispatchCalculation saves snapshot and dispatches when mark ready succeeds`() {
        val job = job()
        val entity = makeSnapshotEntity(jobId = job.jobId)
        val payload = makeRequestedPayload(jobId = job.jobId.toString(), userIgn = job.userIgn, presetNo = job.presetNo)
        whenever(jobPort.markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(true)

        val result = service.saveInputSnapshotAndDispatchCalculation(entity, job.jobId, entity.snapshotId, payload)

        assertThat(result).isTrue()
        verify(snapshotRepository).save(entity)
        verify(jobPort).markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)
        verify(pgmqClient).send(QueueNames.CALCULATION_REQUESTED, payload)
    }

    @Test
    fun `saveInputSnapshotAndDispatchCalculation does not dispatch when mark ready fails`() {
        val job = job()
        val entity = makeSnapshotEntity(jobId = job.jobId)
        val payload = makeRequestedPayload(jobId = job.jobId.toString(), userIgn = job.userIgn, presetNo = job.presetNo)
        whenever(jobPort.markSnapshotReady(job.jobId, entity.snapshotId, CalculationJobStatus.API_REQUESTED)).thenReturn(false)

        val result = service.saveInputSnapshotAndDispatchCalculation(entity, job.jobId, entity.snapshotId, payload)

        assertThat(result).isFalse()
        verify(snapshotRepository).save(entity)
        verify(pgmqClient, never()).send(eq(QueueNames.CALCULATION_REQUESTED), any<CalculationRequestedPayload>())
    }

    @Test
    fun `retryExternalApiJob increments OCID retry when job is OCID_RESOLVING`() {
        val job = job(status = CalculationJobStatus.OCID_RESOLVING)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)
        whenever(jobPort.incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")).thenReturn(true)

        val result = service.retryExternalApiJob(job.jobId, "OCID_RESOLVE_ERROR")

        assertThat(result).isTrue()
        verify(jobPort).incrementRetryForOcid(job.jobId, "OCID_RESOLVE_ERROR")
        verify(pgmqClient).send(QueueNames.EXTERNAL_API, ExternalApiJobPayload(job.jobId.toString(), job.userIgn, job.presetNo))
    }

    @Test
    fun `retryExternalApiJob marks exhausted job failed when retries exceeded`() {
        val job = job(status = CalculationJobStatus.API_REQUESTED, retryCount = 3, maxRetries = 3)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isTrue()
        verify(jobPort).markFailed(job.jobId, "EXTERNAL_API_ERROR", "Max retries exceeded")
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    @Test
    fun `retryExternalApiJob returns false for non-processable job status`() {
        val job = job(status = CalculationJobStatus.COMPLETED)
        whenever(jobPort.findJobById(job.jobId)).thenReturn(job)

        val result = service.retryExternalApiJob(job.jobId)

        assertThat(result).isFalse()
        verify(jobPort, never()).markFailed(eq(job.jobId), any(), any())
        verify(pgmqClient, never()).send(eq(QueueNames.EXTERNAL_API), any<ExternalApiJobPayload>())
    }

    private fun job(
        status: CalculationJobStatus = CalculationJobStatus.REQUESTED,
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

    private fun makeSnapshotEntity(jobId: UUID) = CalculationSnapshotEntity(
        jobId = jobId,
        objectKey = "test-key",
        expiresAt = java.time.Instant.now().plusSeconds(60),
    )

    private fun makeCompletedPayload(jobId: String) = CalculationCompletedPayload(
        jobId = jobId,
        characterId = "char-1",
        characterClass = "WARRIOR",
        presetNo = 1,
        gzipData = ByteArray(0),
        hash = "hash-1",
        originalSize = 0,
        compressedSize = 0,
    )

    private fun makeRequestedPayload(jobId: String, userIgn: String, presetNo: Int) = CalculationRequestedPayload(
        jobId = jobId,
        userIgn = userIgn,
        presetNo = presetNo,
        characterId = "char-1",
        characterClass = "WARRIOR",
    )
}
