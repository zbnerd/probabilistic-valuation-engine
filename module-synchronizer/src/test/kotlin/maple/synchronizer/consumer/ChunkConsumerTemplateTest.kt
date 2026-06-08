package maple.synchronizer.consumer

import maple.expectation.common.event.ChunkExecutionIdentity
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
import maple.synchronizer.metrics.ChunkExecutionMetrics
import maple.synchronizer.repository.ChunkExecutionClaim
import maple.synchronizer.repository.ChunkExecutionState
import maple.synchronizer.repository.ChunkExecutionRepository
import maple.synchronizer.repository.InsertChunkExecutionCommand
import maple.synchronizer.state.ChunkExecutionStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.kafka.support.Acknowledgment
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

class ChunkConsumerTemplateTest {

    private val repository = mock<ChunkExecutionRepository>()
    private val executionMetrics = mock<ChunkExecutionMetrics>()
    private val acknowledgment = mock<Acknowledgment>()
    private val executor = Executor { command -> command.run() }
    private val template = ChunkConsumerTemplate(
        logicExecutor = ImmediateLogicExecutor(),
        chunkExecutionRepository = repository,
        executionMetrics = executionMetrics,
        properties = ChunkExecutionProperties(),
        stateMachine = ChunkExecutionStateMachine(ChunkExecutionProperties()),
    )

    @Test
    fun `first consume inserts pending claims processes marks success then acks`() {
        val events = mutableListOf<String>()
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(attemptCount = 1))
        whenever(repository.markSucceeded(identity, 1)).thenReturn(true)

        template.submit(request(process = { events.add("process") }))

        val order = inOrder(repository, acknowledgment)
        order.verify(repository).insertPendingIfAbsent(any())
        order.verify(repository).findExecutionState(identity)
        order.verify(repository).claimProcessing(eq(identity), any())
        order.verify(repository).markSucceeded(identity, 1)
        order.verify(acknowledgment).acknowledge()
        assertThat(events).containsExactly("process")
    }

    @Test
    fun `succeeded chunk skips processing and acks`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Succeeded))

        template.submit(request(process = { error("must not process") }))

        verify(repository).insertPendingIfAbsent(any())
        verify(repository, never()).claimProcessing(any(), any())
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `processing with active lease acks without permit or claim`() {
        val permit = Semaphore(0)
        whenever(repository.findExecutionState(identity)).thenReturn(
            state(ChunkExecutionStatus.Processing, leaseUntil = Instant.now().plusSeconds(60)),
        )

        template.submit(request(processingPermit = permit, process = { error("must not process") }))

        verify(repository, never()).claimProcessing(any(), any())
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `retryable failure not due does not ack without permit or claim`() {
        val permit = Semaphore(0)
        whenever(repository.findExecutionState(identity)).thenReturn(
            state(ChunkExecutionStatus.FailedRetryable(nextRetryAt = Instant.now().plusSeconds(60)), nextRetryAt = Instant.now().plusSeconds(60)),
        )

        template.submit(request(processingPermit = permit, process = { error("must not process") }))

        verify(repository, never()).claimProcessing(any(), any())
        verify(acknowledgment, never()).acknowledge()
    }

    @Test
    fun `pending chunk with busy permit does not ack or claim`() {
        val permit = Semaphore(0)
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))

        template.submit(request(processingPermit = permit, process = { error("must not process") }))

        verify(repository, never()).claimProcessing(any(), any())
        verify(acknowledgment, never()).acknowledge()
    }

    @Test
    fun `due retryable failure with busy permit ack-skips (no waiting)`() {
        val permit = Semaphore(0)
        whenever(repository.findExecutionState(identity)).thenReturn(
            state(ChunkExecutionStatus.FailedRetryable(nextRetryAt = Instant.now().minusSeconds(60)), nextRetryAt = Instant.now().minusSeconds(60)),
        )

        template.submit(request(processingPermit = permit, process = { error("must not process") }))

        verify(repository, never()).claimProcessing(any(), any())
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `business failure writes retryable failure and preserves Kafka redelivery`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(attemptCount = 1))
        whenever(repository.markFailedRetryable(eq(identity), eq(1), any(), any())).thenReturn(true)

        template.submit(request(process = { error("boom") }))

        verify(repository).markFailedRetryable(eq(identity), eq(1), any(), any())
        verify(acknowledgment, never()).acknowledge()
        verify(repository, never()).markSucceeded(any(), any())
    }

    @Test
    fun `failed state write does not ack`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(attemptCount = 1))
        whenever(repository.markSucceeded(identity, 1)).thenReturn(false)

        template.submit(request())

        verify(acknowledgment, never()).acknowledge()
    }

    @Test
    fun `unsupported schema marks terminal before business process`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(attemptCount = 1))
        whenever(repository.markFailedTerminal(eq(identity), eq(1), any(), eq("UNSUPPORTED_SCHEMA_VERSION"))).thenReturn(true)

        template.submit(request(schemaVersion = 2, process = { error("must not process") }))

        val order = inOrder(repository, acknowledgment)
        order.verify(repository).markFailedTerminal(eq(identity), eq(1), any(), eq("UNSUPPORTED_SCHEMA_VERSION"))
        order.verify(acknowledgment).acknowledge()
    }

    @Test
    fun `artifact missing becomes terminal after artifact missing max attempts`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Pending))
        whenever(repository.claimProcessing(eq(identity), any())).thenReturn(ChunkExecutionClaim(attemptCount = 2))
        whenever(repository.markFailedTerminal(eq(identity), eq(2), any(), eq("ARTIFACT_MISSING_MAX_ATTEMPTS"))).thenReturn(true)

        template.submit(request(process = { throw ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ResultFileReader", "/tmp/missing") }))

        verify(repository).markFailedTerminal(eq(identity), eq(2), any(), eq("ARTIFACT_MISSING_MAX_ATTEMPTS"))
        verify(acknowledgment).acknowledge()
    }

    @Test
    fun `request insert command carries kafka and event metadata`() {
        whenever(repository.findExecutionState(identity)).thenReturn(state(ChunkExecutionStatus.Succeeded))

        template.submit(request())

        val captor = argumentCaptor<InsertChunkExecutionCommand>()
        verify(repository).insertPendingIfAbsent(captor.capture())
        assertThat(captor.firstValue.identity).isEqualTo(identity)
        assertThat(captor.firstValue.topic).isEqualTo("topic-a")
        assertThat(captor.firstValue.messageKey).isEqualTo("key-a")
        assertThat(captor.firstValue.eventType).isEqualTo("EVENT_A")
        assertThat(captor.firstValue.schemaVersion).isEqualTo(1)
        assertThat(captor.firstValue.eventPayloadJson).isEqualTo("""{"eventId":"event-1"}""")
    }

    private fun request(
        schemaVersion: Int = 1,
        processingPermit: Semaphore = Semaphore(1),
        process: () -> Unit = {},
    ) = ChunkConsumerRequest(
        logPrefix = "Test",
        log = LoggerFactory.getLogger(ChunkConsumerTemplateTest::class.java),
        identity = identity,
        topic = "topic-a",
        messageKey = "key-a",
        eventType = "EVENT_A",
        schemaVersion = schemaVersion,
        eventPayloadJson = """{"eventId":"event-1"}""",
        objectKey = "object-key",
        acknowledgment = acknowledgment,
        processingPermit = processingPermit,
        executor = executor,
        processContext = TaskContext.of("Test", "Process", "chunk-1"),
        lifecycleContext = TaskContext.of("Test", "Lifecycle", "chunk-1"),
        process = process,
    )

    private fun state(
        status: ChunkExecutionStatus,
        nextRetryAt: Instant? = null,
        leaseUntil: Instant? = null,
    ) = ChunkExecutionState(
        status = status,
        nextRetryAt = nextRetryAt,
        leaseUntil = leaseUntil,
        attemptCount = 0,
    )

    private class ImmediateLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) = task.run()

        override fun executeVoidJava(task: Runnable, context: TaskContext) = task.run()

        override fun <T> executeWithFinally(
            task: ThrowingSupplier<T>,
            finallyBlock: Runnable,
            context: TaskContext,
        ): T {
            val result = task.get()
            finallyBlock.run()
            return result
        }

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            runCatching { task.get() }.getOrDefault(defaultValue)

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: (Throwable) -> T,
            context: TaskContext,
        ): T = runCatching { task.get() }.getOrElse(fallback)

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: (Throwable) -> T,
            context: TaskContext,
        ): T = runCatching { task.get() }.getOrElse(recovery)

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()
    }

    private companion object {
        private val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            runId = "run-1",
            endpoint = "result",
            chunkId = "chunk-1",
        )
    }
}
