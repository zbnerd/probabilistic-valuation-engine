package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.concurrent.Executor
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.FanOutRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.provider.EquipmentFetchProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * NexonFanOutWorker.processAsync 분기 테스트.
 *
 * <p>processAsync()의 결과 분기(Ack / Nack)만 검증한다.
 * process()는 protected 메서드이므로 직접 호출 대신 processAsync → process 경로를 통해
 * 동기 process()의 true/false 반환을 확인한다.
 *
 * <p>Ack 경로: fetchProvider.fetchWithCache(ocid)가 정상 반환 → executeOrDefault의
 * task.get()이 true 반환 → process()는 true → processAsync는 Ack 반환.
 *
 * <p>Nack 경로: fetchProvider.fetchWithCache(ocid)가 throw → executeOrDefault의
 * task.get()이 throw → 기본값 false 반환 → process()는 false →
 * processAsync는 Nack(retryable=true) 반환.
 */
@DisplayName("NexonFanOutWorker processAsync 분기 테스트")
class NexonFanOutWorkerAsyncTest {

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
     * Required because NexonFanOutWorker.process() delegates to executeOrDefault,
     * which a Mockito mock returns null for, NPEing on `if (process(message))`.
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

    private val payload = FanOutRequest(
        ocid = "ocid-1",
        userIgn = "test-ign",
        retryCount = 0,
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
     * Build a NexonFanOutWorker with all collaborators as mocks.
     */
    private fun buildWorker(
        fetchProvider: EquipmentFetchProvider = mock(),
    ): NexonFanOutWorker = NexonFanOutWorker(
        pgmqClient = mock<PgmqClient>(),
        executor = logicExecutor,
        config = PgmqWorkerConfig(),
        meterRegistry = mock<MeterRegistry>(),
        queueMetrics = mock<WorkerQueueMetrics>(),
        lifecycleWrapper = mock<ScheduledTaskLifecycleWrapper>(),
        fetchProvider = fetchProvider,
        cpuExecutor = cpuExecutor,
    )

    @Test
    fun `processAsync returns Ack when sync process returns true`() {
        // fetchWithCache returns Unit normally → executeOrDefault returns true → process() returns true
        val fetchProvider: EquipmentFetchProvider = mock()
        whenever(fetchProvider.fetchWithCache(any())).thenReturn(mock())
        val worker = buildWorker(fetchProvider = fetchProvider)

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `processAsync returns Nack retryable=true when sync process returns false`() {
        // fetchWithCache throws → executeOrDefault catches → returns default (false) → process() returns false
        val fetchProvider: EquipmentFetchProvider = mock()
        whenever(fetchProvider.fetchWithCache(any())).thenThrow(RuntimeException("nexon boom"))
        val worker = buildWorker(fetchProvider = fetchProvider)

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = result as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }
}