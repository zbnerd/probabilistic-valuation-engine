package maple.expectation.infrastructure.worker

import java.util.UUID
import java.util.concurrent.Executor
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.pgmq.CalculationCompletedPayload
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.ProcessOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * CalculationCompletedWorker.processAsync 분기 테스트.
 *
 * <p>processAsync()의 결과 분기(Ack / Nack)만 검증한다.
 * process()는 protected 메서드이므로 직접 호출 대신 processAsync → process 경로를 통해
 * 동기 process()의 true/false 반환을 확인한다.
 *
 * <p>Ack 경로: jobPort.findJobById가 null을 반환 → persistResult가 early-return true →
 * process()는 true를 반환 → processAsync는 Ack를 반환.
 *
 * <p>Nack 경로: executionService.completeCalculatedResult가 throw → stage() throws →
 * executeOrCatch recovery path → handleFailure(message, jobId) 호출 →
 * readCount(1) < maxRetries(3) 이면 false 반환 → process()는 false →
 * processAsync는 Nack(retryable=true)를 반환.
 */
@DisplayName("CalculationCompletedWorker processAsync 분기 테스트")
class CalculationCompletedWorkerAsyncTest {

    /**
     * Synchronous executor stub: `execute(Runnable)` runs the runnable inline on the calling thread.
     * Avoids the per-test single-thread executor that was never closed.
     */
    private val cpuExecutor: Executor = mock<Executor>().apply {
        whenever(execute(any())).then { invocation ->
            (invocation.arguments[0] as Runnable).run()
            null
        }
    }

    /**
     * Real LogicExecutor implementation that runs everything inline.
     * Required because CalculationCompletedWorker.process() delegates to executeOrCatch,
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

    private val payload = CalculationCompletedPayload(
        jobId = "11111111-1111-1111-1111-111111111111",
        characterId = "ocid-1",
        characterClass = "WARRIOR",
        presetNo = 1,
        gzipData = ByteArray(0),
        hash = "hash",
        originalSize = 0,
        compressedSize = 0,
    )
    private val jobId = UUID.fromString(payload.jobId)
    private val baseMessage = PgmqMessage(
        messageId = 1L,
        readCount = 1,
        enqueuedAt = java.time.Instant.now(),
        visibilityTimeout = java.time.Instant.now().plusSeconds(30),
        payload = payload,
    )

    /**
     * Build a CalculationCompletedWorker with all collaborators as mocks.
     * Use a real PgmqWorkerConfig (with non-null `common`) instead of a Mockito mock —
     * the abstract PgmqWorker constructor dereferences `config.common.pipelineMicroBatchSize`
     * eagerly, which a mock returns null for and NPEs.
     */
    private fun buildWorker(
        jobPort: CalculationJobPort = mock(),
        executionService: CalculationExecutionService = mock(),
    ): CalculationCompletedWorker = CalculationCompletedWorker(
        pgmqClient = mock(),
        executor = logicExecutor,
        workerConfig = PgmqWorkerConfig(),
        meterRegistry = mock(),
        queueMetrics = mock(),
        lifecycleWrapper = mock(),
        jobPort = jobPort,
        executionService = executionService,
        cpuExecutor = cpuExecutor,
    )

    @Test
    fun `processAsync returns Ack when sync process returns true`() {
        // findJobById returns null → persistResult early-returns true → process() returns true
        val jobPort: CalculationJobPort = mock()
        whenever(jobPort.findJobById(jobId)).thenReturn(null)
        val worker = buildWorker(jobPort = jobPort)

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `processAsync returns Nack retryable=true when sync process returns false`() {
        // executionService.completeCalculatedResult throws → stage() throws → executeOrCatch
        // invokes handleFailure → readCount(1) < maxRetries(3) → handleFailure returns false →
        // process() returns false → processAsync returns Nack(retryable=true).
        val jobPort: CalculationJobPort = mock()
        val executionService: CalculationExecutionService = mock()
        whenever(jobPort.findJobById(jobId)).thenReturn(
            CalculationJob(
                jobId = jobId,
                ocid = "ocid-1",
                userIgn = "test-ign",
                status = CalculationJobStatus.CALCULATING,
                retryCount = 0,
                maxRetries = 3,
                presetNo = 1,
            ),
        )
        whenever(
            executionService.completeCalculatedResult(
                jobId = any(),
                gzipData = any(),
                hash = any(),
                originalSize = any(),
                compressedSize = any(),
                characterClass = any(),
                presetNo = any(),
                characterId = any(),
                totalExpectedCost = any(),
                maxPresetNo = any(),
                presetsJson = any(),
            ),
        ).doThrow(RuntimeException("persist boom"))

        val worker = buildWorker(
            jobPort = jobPort,
            executionService = executionService,
        )

        val result = worker.callProcessAsync(baseMessage).get()

        assertThat(result).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = result as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }
}