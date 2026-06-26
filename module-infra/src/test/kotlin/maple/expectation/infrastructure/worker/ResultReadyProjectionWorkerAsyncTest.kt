package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultLight
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ResultReadyProjectionWorker async-chain 테스트.
 *
 * <p>projectPgmqBatch()이 CompletableFuture<Void>를 반환하고,
 * 정상 케이스에서는 completedFuture, 예외 케이스에서는 exceptionally로 끝나는지 검증.
 *
 * <p>asyncExecutor는 inline executor로 mock — supplyAsync 호출이 caller 스레드에서 즉시 실행됨.
 * LogicExecutor는 inline 구현 — executeVoid가 runnable을 직접 실행.
 */
@DisplayName("ResultReadyProjectionWorker async-chain 테스트")
class ResultReadyProjectionWorkerAsyncTest {

    /**
     * Synchronous executor stub: supplyAsync 즉시 caller 스레드에서 실행.
     * ExecutorService로 mock — Executor 타입은 ExecutorService로 다운캐스트 불가.
     */
    private val asyncExecutor: java.util.concurrent.ExecutorService = mock<java.util.concurrent.ExecutorService>().apply {
        whenever(execute(any())).then { invocation ->
            (invocation.arguments[0] as Runnable).run()
            null
        }
    }

    /**
     * Real LogicExecutor implementation that runs everything inline.
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

    private val jobId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val baseJob = CalculationJob(
        jobId = jobId,
        ocid = "ocid-1",
        userIgn = "test-ign",
        status = CalculationJobStatus.API_REQUESTED,
        retryCount = 0,
        maxRetries = 3,
        presetNo = 1,
    )
    private val baseLight = CalculationResultLight(
        jobId = jobId,
        characterClass = "WARRIOR",
        presetNo = 1,
        totalExpectedCost = 1000L,
        maxPresetNo = 3,
        presetsJson = "[{\"presetNo\":1}]",
    )
    private val baseMessage: PgmqMessage<Map<*, *>> = PgmqMessage(
        messageId = 1L,
        readCount = 1,
        enqueuedAt = Instant.now(),
        visibilityTimeout = Instant.now().plusSeconds(30),
        payload = mapOf<String, Any>("jobId" to jobId.toString(), "presetNo" to 1, "characterId" to "ocid-1"),
    )

    private fun buildWorker(
        pgmqClient: PgmqClient = mock(),
        jobPort: CalculationJobPort = mock(),
        resultPort: CalculationResultPort = mock(),
        viewQueryPort: CharacterViewQueryPort = mock(),
    ): ResultReadyProjectionWorker = ResultReadyProjectionWorker(
        pgmqClient = pgmqClient,
        jobPort = jobPort,
        resultPort = resultPort,
        viewQueryPort = viewQueryPort,
        executor = logicExecutor,
        objectMapper = ObjectMapper(),
        asyncExecutor = asyncExecutor,
        batchSize = 100,
        visibilityTimeoutSec = 30,
        stepTraceThresholdMs = 500L,
    )

    @Test
    fun `projectPgmqBatch returns CompletableFuture that completes normally on empty input`() {
        val worker = buildWorker()

        val future = worker.callProjectPgmqBatch(emptyList())

        // 정상 케이스: completedFuture(null) 반환 → join 시 null, 예외 없음
        assertThat(future).isNotNull
        assertThat(future.isDone).isTrue
        assertThat(future.isCompletedExceptionally).isFalse
    }

    @Test
    fun `projectPgmqBatch completes exceptionally when findJobsByIds throws`() {
        val jobPort: CalculationJobPort = mock()
        whenever(jobPort.findJobsByIds(any())).doThrow(RuntimeException("DB boom"))
        val resultPort: CalculationResultPort = mock()
        val worker = buildWorker(jobPort = jobPort, resultPort = resultPort)

        val messages: List<PgmqMessage<Map<*, *>>> = listOf(baseMessage)
        val future = worker.callProjectPgmqBatch(messages)

        // 예외 케이스: future가 exceptionally로 완료되어야 함
        assertThat(future.isCompletedExceptionally).isTrue
    }
}
