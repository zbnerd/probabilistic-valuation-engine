package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class PgmqWorkerProcessAsyncTest {

    private fun newTestWorker(
        processResult: Boolean,
        queueName: String = "test_queue",
    ): TestWorker {
        val config = PgmqWorkerConfig()
        val queueMetrics = WorkerQueueMetrics(SimpleMeterRegistry())
        val lifecycleWrapper = mock<ScheduledTaskLifecycleWrapper>()
        val pgmqClient: PgmqClient = mock()
        val executor: maple.expectation.infrastructure.executor.LogicExecutor = mock()
        return TestWorker(
            pgmqClient = pgmqClient,
            executor = executor,
            config = config,
            meterRegistry = SimpleMeterRegistry(),
            queueMetrics = queueMetrics,
            lifecycleWrapper = lifecycleWrapper,
            testQueueName = queueName,
            processResult = processResult,
        )
    }

    private fun testMessage(): PgmqMessage<ExpectationCalcMessage> = PgmqMessage.of(
        messageId = 1L,
        readCount = 0,
        enqueuedAt = Instant.now(),
        vt = Instant.now().plusSeconds(30),
        payload = ExpectationCalcMessage(userIgn = "TestUser", forceRecalculation = false),
    )

    @Test
    @DisplayName("processAsync returns Ack when sync process() returns true")
    fun `processAsync returns Ack when process returns true`() {
        val worker = newTestWorker(processResult = true)
        val message = testMessage()

        val outcome = worker.callProcessAsync(message)
            .get(5, TimeUnit.SECONDS)

        assertThat(outcome).isInstanceOf(ProcessOutcome.Ack::class.java)
    }

    @Test
    @DisplayName("processAsync returns Nack(retryable=true) when sync process() returns false")
    fun `processAsync returns Nack when process returns false`() {
        val worker = newTestWorker(processResult = false)
        val message = testMessage()

        val outcome = worker.callProcessAsync(message)
            .get(5, TimeUnit.SECONDS)

        assertThat(outcome).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = outcome as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }

    @Test
    @DisplayName("overridden processAsync is used in preference to default")
    fun `overridden processAsync takes precedence`() {
        val worker = newTestWorker(processResult = true).also {
            it.overrideProcessAsyncToReturn(ProcessOutcome.DeadLetter("override"))
        }
        val message = testMessage()

        val outcome = worker.callProcessAsync(message)
            .get(5, TimeUnit.SECONDS)

        assertThat(outcome).isInstanceOf(ProcessOutcome.DeadLetter::class.java)
        assertThat((outcome as ProcessOutcome.DeadLetter).reason).isEqualTo("override")
    }

    /**
     * Minimal PgmqWorker subclass exposing access to the protected [PgmqWorker.processAsync]
     * via a public bridge method. Mirrors the constructor signature of the production class.
     */
    private class TestWorker(
        pgmqClient: PgmqClient,
        executor: maple.expectation.infrastructure.executor.LogicExecutor,
        config: PgmqWorkerConfig,
        meterRegistry: io.micrometer.core.instrument.MeterRegistry,
        queueMetrics: WorkerQueueMetrics,
        lifecycleWrapper: ScheduledTaskLifecycleWrapper,
        testQueueName: String,
        private val processResult: Boolean,
    ) : PgmqWorker<ExpectationCalcMessage>(
        pgmqClient,
        executor,
        config,
        meterRegistry,
        queueMetrics,
        lifecycleWrapper,
    ) {
        override val queueName: String = testQueueName
        override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java
        override val workerSettings: PgmqWorkerConfig.WorkerSettings = PgmqWorkerConfig.WorkerSettings(enabled = false)

        override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean = processResult

        /** Bridge so the test can call the protected [processAsync]. */
        fun callProcessAsync(message: PgmqMessage<ExpectationCalcMessage>): CompletableFuture<ProcessOutcome> =
            processAsync(message)

        private var overrideOutcome: ProcessOutcome? = null

        fun overrideProcessAsyncToReturn(outcome: ProcessOutcome) {
            overrideOutcome = outcome
        }

        override fun processAsync(message: PgmqMessage<ExpectationCalcMessage>): CompletableFuture<ProcessOutcome> {
            overrideOutcome?.let { return CompletableFuture.completedFuture(it) }
            return super.processAsync(message)
        }
    }
}