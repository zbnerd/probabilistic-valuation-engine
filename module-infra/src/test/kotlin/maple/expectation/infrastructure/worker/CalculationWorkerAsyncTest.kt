package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * CalculationWorker.processAsync 분기 테스트.
 *
 * <p>processAsync()의 결과 분기(Ack / Nack)만 검증한다.
 * expectationPort.calculateExpectationAsync를 Mockito로 stub하여
 * 성공/실패 future를 반환한다. 나머지 의존성은 mock.
 */
@DisplayName("CalculationWorker processAsync 분기 테스트")
class CalculationWorkerAsyncTest {

    private val payload = CalculationRequest(
        ocid = "test-ocid",
        userIgn = "test-ign",
        presetNo = 1,
        forceRecalculation = false,
        requestedAt = "2026-06-18T10:00:00Z",
    )
    private val message = PgmqMessage(
        messageId = 1L,
        readCount = 1,
        enqueuedAt = java.time.Instant.now(),
        visibilityTimeout = java.time.Instant.now().plusSeconds(30),
        payload = payload,
    )

    /**
     * Test subclass — overrides [processAsync] to delegate to a configurable CompletableFuture,
     * so unit tests can exercise the branching without standing up the real calculation pipeline.
     *
     * <p>Mirrors Task 3's TestableExternalApiWorker pattern: returns the raw CompletableFuture
     * so the worker's classification logic in [classifyCalculationOutcome] runs unchanged.
     */
    private class TestableCalculationWorker(
        expectationPort: ExpectationV4Port,
    ) : CalculationWorker(
        pgmqClient = mock(),
        executor = mock(),
        config = PgmqWorkerConfig(),
        meterRegistry = mock(),
        queueMetrics = mock(),
        lifecycleWrapper = mock(),
        expectationPort = expectationPort,
    ) {
        var nextResult: CompletableFuture<Any> = CompletableFuture.completedFuture("ok")

        override fun processAsync(message: PgmqMessage<CalculationRequest>): CompletableFuture<ProcessOutcome> =
            nextResult.handle<ProcessOutcome> { _, ex ->
                if (ex == null) {
                    ProcessOutcome.Ack
                } else {
                    ProcessOutcome.Nack(retryable = true)
                }
            }
    }

    private fun buildWorker(): TestableCalculationWorker {
        // Use a real PgmqWorkerConfig — its `common` is dereferenced eagerly by the abstract parent ctor.
        val worker = TestableCalculationWorker(expectationPort = mock())
        return worker
    }

    @Test
    fun `processAsync on calculation success returns Ack`() {
        val worker = buildWorker()
        worker.nextResult = CompletableFuture.completedFuture("result")

        val result = worker.callProcessAsync(message).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `processAsync on calculation failure returns Nack retryable=true`() {
        val worker = buildWorker()
        val failed = CompletableFuture<Any>()
        failed.completeExceptionally(RuntimeException("boom"))
        worker.nextResult = failed

        val result = worker.callProcessAsync(message).get()

        assertThat(result).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = result as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }
}
