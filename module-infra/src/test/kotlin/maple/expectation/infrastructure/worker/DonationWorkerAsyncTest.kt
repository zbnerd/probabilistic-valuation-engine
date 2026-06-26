package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.concurrent.Executor
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.port.out.AlertPublisher
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.DonationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * DonationWorker.processAsync 분기 테스트.
 *
 * <p>processAsync()의 결과 분기(Ack / Nack)만 검증한다.
 * process()는 protected 메서드이므로 직접 호출 대신 processAsync → process 경로를 통해
 * 동기 process()의 true/false 반환을 확인한다.
 *
 * <p>Ack 경로: alertPublisher.sendInfo 정상 호출 → process()는 true → processAsync는 Ack를 반환.
 *
 * <p>Nack 경로: alertPublisher.sendInfo throw → executeOrDefault이 default(false) 반환 →
 * process()는 false → processAsync는 Nack(retryable=true)를 반환.
 */
@DisplayName("DonationWorker processAsync 분기 테스트")
class DonationWorkerAsyncTest {

    /**
     * Synchronous executor stub: `execute(Runnable)` runs the runnable inline on the calling thread.
     */
    private val cpuExecutor: Executor = mock<Executor>().apply {
        whenever(execute(any())).then { invocation ->
            (invocation.arguments[0] as Runnable).run()
            null
        }
    }

    /**
     * Real LogicExecutor implementation that runs everything inline.
     * Required because DonationWorker.process() delegates to executeOrDefault,
     * which a Mockito mock returns null for, NPEing on Boolean unbox.
     */
    private val logicExecutor: LogicExecutor = object : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            runCatching { task.get() ?: defaultValue }.getOrElse { defaultValue }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            task.run()
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T = try {
            task.get()
        } finally {
            finallyBlock.run()
        }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = try {
            task.get()
        } catch (e: Exception) {
            throw customTranslator.translate(e, context)
        }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T =
            runCatching { task.get() }.getOrElse { fallback(it) }

        override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ExceptionTranslator, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            throw fallback.translate(e, context)
        }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T =
            runCatching { task.get() }.getOrElse { recovery(it) }

        override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: ExceptionTranslator, context: TaskContext): T = try {
            task.get()
        } catch (e: Exception) {
            throw recovery.translate(e, context)
        }
    }

    private val payload = DonationRequest(
        donationId = 1L,
        userId = 100L,
        amount = 5_000L,
        message = "Great work!",
        requestedAt = "2026-06-18T00:00:00Z",
    )
    private val baseMessage = PgmqMessage(
        messageId = 1L,
        readCount = 1,
        enqueuedAt = Instant.now(),
        visibilityTimeout = Instant.now().plusSeconds(30),
        payload = payload,
    )

    /**
     * Build a DonationWorker with all collaborators as mocks.
     * Use a real PgmqWorkerConfig (with non-null `common`) instead of a Mockito mock —
     * the abstract PgmqWorker constructor dereferences `config.common.pipelineMicroBatchSize`
     * eagerly, which a mock returns null for and NPEs.
     */
    private fun buildWorker(
        alertPublisher: AlertPublisher = mock(),
    ): DonationWorker = DonationWorker(
        pgmqClient = mock(),
        executor = logicExecutor,
        config = PgmqWorkerConfig(),
        meterRegistry = mock<MeterRegistry>(),
        queueMetrics = mock<WorkerQueueMetrics>(),
        lifecycleWrapper = mock<ScheduledTaskLifecycleWrapper>(),
        alertPublisher = alertPublisher,
        cpuExecutor = cpuExecutor,
    )

    @Test
    fun `processAsync returns Ack when sync process returns true`() {
        val alertPublisher: AlertPublisher = mock()
        val worker = buildWorker(alertPublisher = alertPublisher)

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `processAsync returns Nack retryable=true when sync process returns false`() {
        val alertPublisher: AlertPublisher = mock<AlertPublisher>().apply {
            whenever(sendInfo(any(), any())).doThrow(RuntimeException("alert boom"))
        }
        val worker = buildWorker(alertPublisher = alertPublisher)

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = result as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }
}