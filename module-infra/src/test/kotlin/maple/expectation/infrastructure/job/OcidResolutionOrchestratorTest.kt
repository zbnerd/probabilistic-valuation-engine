package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.event.OcidResolveEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class OcidResolutionOrchestratorTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var ocidResolveTopic: OcidResolveTopic

    private lateinit var service: OcidResolutionOrchestrator

    @BeforeEach
    fun setUp() {
        service = OcidResolutionOrchestrator(
            jobPort = jobPort,
            eventAppender = eventAppender,
            ocidResolveTopic = ocidResolveTopic,
        )
    }

    @Test
    fun `requestOcidResolve enqueues event on successful transition`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
            .thenReturn(true)

        service.requestOcidResolve(jobId, "testIgn", 1)

        val captor = argumentCaptor<IntegrationEvent<*>>()
        verify(eventAppender).append(eq(ocidResolveTopic), captor.capture())
        val captured = captor.firstValue
        assertThat(captured.eventType).isEqualTo("OCID_RESOLVE")
        assertThat(captured.jobId).isEqualTo(jobId.toString())
        @Suppress("UNCHECKED_CAST")
        val payload = captured.payload as Map<String, Any>
        assertThat(payload["jobId"]).isEqualTo(jobId.toString())
        assertThat(payload["userIgn"]).isEqualTo("testIgn")
        assertThat(payload["presetNo"]).isEqualTo(1)
    }

    @Test
    fun `requestOcidResolve skips enqueue when transition fails`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.REQUESTED, CalculationJobStatus.OCID_RESOLVING))
            .thenReturn(false)

        service.requestOcidResolve(jobId, "testIgn", 1)

        verify(eventAppender, never()).append(any(), any())
    }

    @Test
    fun `handleOcidFailure marks failed when max retries exceeded`() {
        val jobId = UUID.randomUUID()
        val job = CalculationJob(
            jobId = jobId, ocid = null, userIgn = "ign", presetNo = 1,
            status = CalculationJobStatus.OCID_RESOLVING, retryCount = 5, maxRetries = 5,
        )
        whenever(jobPort.findJobById(jobId)).thenReturn(job)

        service.handleOcidFailure(jobId, "CODE", "boom")

        verify(jobPort).markFailed(jobId, "CODE", "boom")
        verify(eventAppender, never()).append(any(), any())
    }

    @Test
    fun `handleOcidFailure re-enqueues OCID resolve on retry`() {
        val jobId = UUID.randomUUID()
        val job = CalculationJob(
            jobId = jobId, ocid = null, userIgn = "ign", presetNo = 1,
            status = CalculationJobStatus.OCID_RESOLVING, retryCount = 0, maxRetries = 5,
        )
        whenever(jobPort.findJobById(jobId)).thenReturn(job)
        whenever(jobPort.incrementRetryForOcid(jobId, "CODE")).thenReturn(true)

        service.handleOcidFailure(jobId, "CODE", "boom")

        val captor = argumentCaptor<IntegrationEvent<*>>()
        verify(eventAppender).append(eq(ocidResolveTopic), captor.capture())
        @Suppress("UNCHECKED_CAST")
        val payload = captor.firstValue.payload as Map<String, Any>
        assertThat(payload["jobId"]).isEqualTo(jobId.toString())
        assertThat(payload["userIgn"]).isEqualTo("ign")
        assertThat(payload["presetNo"]).isEqualTo(1)
    }

    @Test
    fun `resolveOcidInPlace delegates to jobPort`() {
        val jobId = UUID.randomUUID()
        whenever(jobPort.resolveOcidAndTransition(jobId, "ocid-1")).thenReturn(true)

        val result = service.resolveOcidInPlace(jobId, "ocid-1")

        assertThat(result).isTrue()
        verify(jobPort).resolveOcidAndTransition(jobId, "ocid-1")
    }
}
