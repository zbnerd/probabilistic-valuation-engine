package maple.synchronizer.consumer

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.metrics.ChunkExecutionMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionRepository
import maple.synchronizer.repository.ChunkExecutionState
import maple.synchronizer.repository.InsertChunkExecutionCommand
import maple.synchronizer.state.ChunkExecutionStateMachine
import maple.synchronizer.state.ChunkExecutionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ChunkConsumerTemplateTest {
    private val repository = mock<ChunkExecutionRepository>()
    private val executionMetrics = mock<ChunkExecutionMetrics>()
    private val directExecutor = Executor(Runnable::run)
    private val template = ChunkConsumerTemplate(
        chunkExecutionRepository = repository,
        executionMetrics = executionMetrics,
        properties = ChunkExecutionProperties(),
        stateMachine = ChunkExecutionStateMachine(ChunkExecutionProperties()),
    )

    @Test
    fun `process publish completion mark success and Success are strictly ordered`() {
        val calls = mutableListOf<String>()
        val publish = CompletableFuture<Void>()
        givenClaimable()
        whenever(repository.markSucceeded(identity, 1)) doAnswer {
            calls += "markSucceeded"
            true
        }
        val delivery = template.submit(
            request(
                process = { calls += "process" },
                publishRequired = {
                    calls += "publish invoked"
                    publish
                },
                onObservedSuccess = { calls += "observed success" },
            ),
        ).toCompletableFuture()

        assertThat(calls).containsExactly("process", "publish invoked")
        assertThat(delivery).isNotDone()

        calls += "publish completed"
        publish.complete(null)

        assertThat(delivery).isCompletedWithValue(DeliveryOutcome.Success)
        assertThat(calls).containsExactly(
            "process",
            "publish invoked",
            "publish completed",
            "markSucceeded",
            "observed success",
        )
    }

    @Test
    fun `publish failure records business retry state skips success and returns Retryable`() {
        val failure = IllegalStateException("publish failed")
        givenClaimable()
        whenever(repository.markFailedRetryable(eq(identity), eq(1), any(), any())).thenReturn(true)

        val outcome = template.submit(
            request(publishRequired = { CompletableFuture.failedFuture(failure) }),
        ).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Retryable(failure))
        verify(repository, never()).markSucceeded(any(), any())
        verify(repository).markFailedRetryable(eq(identity), eq(1), any(), any())
    }

    @Test
    fun `lost mark success race is Retryable rather than Success`() {
        givenClaimable()
        whenever(repository.markSucceeded(identity, 1)).thenReturn(false)

        val outcome = template.submit(request()).toCompletableFuture().resultNow()

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Retryable::class.java)
    }

    @Test
    fun `permit is released for success process failure publish failure cancellation and state failure`() {
        assertPermitReleased(
            configure = {
                givenClaimable()
                whenever(repository.markSucceeded(identity, 1)).thenReturn(true)
            },
            requestFactory = { permit -> request(processingPermit = permit) },
        )
        assertPermitReleased(
            configure = {
                givenClaimable()
                whenever(repository.markFailedRetryable(eq(identity), eq(1), any(), any())).thenReturn(true)
            },
            requestFactory = { permit -> request(processingPermit = permit, process = { error("process") }) },
        )
        assertPermitReleased(
            configure = {
                givenClaimable()
                whenever(repository.markFailedRetryable(eq(identity), eq(1), any(), any())).thenReturn(true)
            },
            requestFactory = { permit ->
                request(
                    processingPermit = permit,
                    publishRequired = { CompletableFuture.failedFuture(IllegalStateException("publish")) },
                )
            },
        )
        assertPermitReleased(
            configure = {
                givenClaimable()
                whenever(repository.markFailedRetryable(eq(identity), eq(1), any(), any())).thenReturn(true)
            },
            requestFactory = { permit ->
                val cancelled = CompletableFuture<Void>()
                cancelled.cancel(false)
                request(processingPermit = permit, publishRequired = { cancelled })
            },
        )
        assertPermitReleased(
            configure = {
                givenClaimable()
                whenever(repository.markSucceeded(identity, 1)).thenThrow(IllegalStateException("state"))
            },
            requestFactory = { permit -> request(processingPermit = permit) },
        )
    }

    @Test
    fun `future next retry and unavailable permit are Backpressure`() {
        val futureRetry = Instant.now().plusSeconds(60)
        whenever(repository.findExecutionState(identity)).thenReturn(
            state(ChunkExecutionStatus.FailedRetryable(futureRetry), nextRetryAt = futureRetry),
        )
        val dueLater = template.submit(request()).toCompletableFuture().resultNow()

        val noCapacity = template.submit(request(processingPermit = Semaphore(0))).toCompletableFuture().resultNow()

        assertThat(dueLater).isInstanceOf(DeliveryOutcome.Backpressure::class.java)
        assertThat(noCapacity).isInstanceOf(DeliveryOutcome.Backpressure::class.java)
        verify(repository, never()).claimProcessing(any(), any())
    }

    @Test
    fun `executor rejection releases acquired permit and returns Backpressure`() {
        val permit = Semaphore(1)
        val rejecting = Executor { throw RejectedExecutionException("full") }

        val outcome = template.submit(
            request(processingPermit = permit, executor = rejecting),
        ).toCompletableFuture().resultNow()

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Backpressure::class.java)
        assertThat(permit.availablePermits()).isEqualTo(1)
    }

    @Test
    fun `unsupported schema persists terminal state then returns InvalidMessage`() {
        givenClaimable()
        whenever(repository.markFailedTerminal(eq(identity), eq(1), any(), eq("UNSUPPORTED_SCHEMA_VERSION")))
            .thenReturn(true)

        val outcome = template.submit(request(schemaVersion = 2)).toCompletableFuture().resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.InvalidMessage("UNSUPPORTED_SCHEMA_VERSION"))
        verify(repository).markFailedTerminal(eq(identity), eq(1), any(), eq("UNSUPPORTED_SCHEMA_VERSION"))
    }

    @Test
    fun `succeeded duplicate is Success without processing`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Succeeded))

        val outcome = template.submit(request(process = { error("must not process") }))
            .toCompletableFuture()
            .resultNow()

        assertThat(outcome).isEqualTo(DeliveryOutcome.Success)
        verify(repository, never()).claimProcessing(any(), any())
    }

    @Test
    fun `insert command keeps Kafka event identity metadata`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Succeeded))

        template.submit(request()).toCompletableFuture().resultNow()

        val captor = org.mockito.kotlin.argumentCaptor<InsertChunkExecutionCommand>()
        verify(repository).insertPendingIfAbsent(captor.capture())
        assertThat(captor.firstValue).isEqualTo(
            InsertChunkExecutionCommand(
                identity = identity,
                topic = "topic-a",
                messageKey = "key-a",
                eventType = "EVENT_A",
                schemaVersion = 1,
                eventPayloadJson = """{"eventId":"event-1"}""",
            ),
        )
    }

    private fun assertPermitReleased(
        configure: () -> Unit,
        requestFactory: (Semaphore) -> ChunkConsumerRequest,
    ) {
        org.mockito.kotlin.reset(repository)
        configure()
        val permit = Semaphore(1)

        template.submit(requestFactory(permit)).toCompletableFuture().resultNow()

        assertThat(permit.availablePermits()).isEqualTo(1)
    }

    private fun givenClaimable() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(1))
    }

    private fun request(
        schemaVersion: Int = 1,
        processingPermit: Semaphore = Semaphore(1),
        executor: Executor = directExecutor,
        process: () -> Unit = {},
        publishRequired: () -> java.util.concurrent.CompletionStage<Void> = {
            CompletableFuture.completedFuture(null)
        },
        onObservedSuccess: () -> Unit = {},
    ) = ChunkConsumerRequest(
        identity = identity,
        topic = "topic-a",
        messageKey = "key-a",
        eventType = "EVENT_A",
        schemaVersion = schemaVersion,
        eventPayloadJson = """{"eventId":"event-1"}""",
        processingPermit = processingPermit,
        executor = executor,
        process = process,
        publishRequired = publishRequired,
        onObservedSuccess = onObservedSuccess,
        onObservedFailure = {},
    )

    private fun state(
        status: ChunkExecutionStatus,
        nextRetryAt: Instant? = null,
        leaseUntil: Instant? = null,
    ) = ChunkExecutionState(status, nextRetryAt, leaseUntil, attemptCount = 0)

    private companion object {
        private val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            runId = "run-1",
            endpoint = "result",
            chunkId = "chunk-1",
        )
    }
}
