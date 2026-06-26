package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.PureCalculationPort
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.infrastructure.converter.EquipmentResponseToCalculationInputConverter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.job.CalculationExecutionService
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.job.OcidResolutionOrchestrator
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.ExternalApiJobPayload
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
 * ExternalApiWorker.processAsync 분기 테스트.
 *
 * <p>processAsync()의 결과 분기(Ack / Nack)만 검증한다.
 * pipelineAsync()는 `internal open`이라 테스트 subclass에서 직접 override하여
 * 동기적으로 완료/실패하는 future를 반환한다. 나머지 의존성은 mock.
 *
 * <p>CHARACTER_NOT_FOUND 분기: pipelineAsync가 [CharacterNotFoundException]을 던지면
 * processAsync는 jobPort.markFailed를 호출하고 Ack를 반환해야 한다
 * (기존 sync `true` → skip retry 동작 보존).
 */
@DisplayName("ExternalApiWorker processAsync 분기 테스트")
class ExternalApiWorkerAsyncTest {

    /**
     * Test subclass — overrides [pipelineAsync] to return a configurable CompletableFuture,
     * so unit tests can exercise the processAsync branching without standing up the real pipeline.
     */
    private class TestableExternalApiWorker(
        pgmqClient: PgmqClient,
        executor: LogicExecutor,
        workerConfig: PgmqWorkerConfig,
        meterRegistry: MeterRegistry,
        queueMetrics: WorkerQueueMetrics,
        lifecycleWrapper: ScheduledTaskLifecycleWrapper,
        nexonApiClient: NexonApiClient,
        equipmentFetchProvider: EquipmentFetchProvider,
        snapshotStore: SnapshotObjectStore,
        jobService: CalculationJobService,
        ocidOrchestrator: OcidResolutionOrchestrator,
        executionService: CalculationExecutionService,
        objectMapper: ObjectMapper,
        converter: EquipmentResponseToCalculationInputConverter,
        calculationInputPort: CalculationInputPort,
        jobPort: CalculationJobPort,
        ocidPort: CharacterOcidPort,
        pureCalculationPort: PureCalculationPort,
        cpuExecutor: Executor,
    ) : ExternalApiWorker(
        pgmqClient,
        executor,
        workerConfig,
        meterRegistry,
        queueMetrics,
        lifecycleWrapper,
        nexonApiClient,
        equipmentFetchProvider,
        snapshotStore,
        jobService,
        ocidOrchestrator,
        executionService,
        objectMapper,
        converter,
        calculationInputPort,
        jobPort,
        ocidPort,
        pureCalculationPort,
        cpuExecutor,
        consolidatedEnabled = true,
        stepTraceThresholdMs = 500L,
    ) {
        var nextPipelineResult: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        var nextPipelineException: Throwable? = null

        override fun pipelineAsync(payload: ExternalApiJobPayload): CompletableFuture<Unit> {
            val ex = nextPipelineException
            return if (ex == null) {
                nextPipelineResult
            } else {
                val failed = CompletableFuture<Unit>()
                failed.completeExceptionally(ex)
                failed
            }
        }
    }

    /**
     * Synchronous executor stub: `execute(Runnable)` runs the runnable inline on the calling thread.
     * Avoids the per-test single-thread executor that was never closed (Fix 4).
     */
    private val cpuExecutor: Executor = mock<Executor>().apply {
        whenever(execute(any())).then { invocation ->
            (invocation.arguments[0] as Runnable).run()
            null
        }
    }

    private val payload = ExternalApiJobPayload(
        jobId = "11111111-1111-1111-1111-111111111111",
        userIgn = "test-ign",
        presetNo = 1,
    )
    private val message = PgmqMessage(
        messageId = 1L,
        readCount = 1,
        enqueuedAt = java.time.Instant.now(),
        visibilityTimeout = java.time.Instant.now().plusSeconds(30),
        payload = payload,
    )

    private fun buildWorker(): Pair<TestableExternalApiWorker, CalculationJobPort> {
        val jobPort: CalculationJobPort = mock()
        // Use a real PgmqWorkerConfig (with non-null `common`) instead of a Mockito mock —
        // the abstract PgmqWorker constructor dereferences `config.common.pipelineMicroBatchSize`
        // eagerly, which a mock returns null for and NPEs.
        val workerConfig = PgmqWorkerConfig()
        val worker = TestableExternalApiWorker(
            pgmqClient = mock(),
            executor = mock(),
            workerConfig = workerConfig,
            meterRegistry = mock(),
            queueMetrics = mock(),
            lifecycleWrapper = mock(),
            nexonApiClient = mock(),
            equipmentFetchProvider = mock(),
            snapshotStore = mock(),
            jobService = mock(),
            ocidOrchestrator = mock(),
            executionService = mock(),
            objectMapper = mock(),
            converter = mock(),
            calculationInputPort = mock(),
            jobPort = jobPort,
            ocidPort = mock(),
            pureCalculationPort = mock(),
            cpuExecutor = cpuExecutor,
        )
        return worker to jobPort
    }

    @Test
    fun `processAsync on pipeline success returns Ack`() {
        val (worker, _) = buildWorker()
        worker.nextPipelineResult = CompletableFuture.completedFuture(Unit)

        val result = worker.callProcessAsync(message).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `processAsync on CharacterNotFound returns Ack and marks job failed (skip retry)`() {
        val (worker, jobPort) = buildWorker()
        worker.nextPipelineException = CharacterNotFoundException("test-ign")

        val result = worker.callProcessAsync(message).get()

        assertThat(result).isEqualTo(ProcessOutcome.Ack)
        org.mockito.kotlin.verify(jobPort).markFailed(
            org.mockito.kotlin.eq(java.util.UUID.fromString(payload.jobId)),
            org.mockito.kotlin.eq("CHARACTER_NOT_FOUND"),
            org.mockito.kotlin.any(),
        )
    }

    @Test
    fun `processAsync on generic failure returns Nack retryable=true`() {
        val (worker, _) = buildWorker()
        worker.nextPipelineException = RuntimeException("boom")

        val result = worker.callProcessAsync(message).get()

        assertThat(result).isInstanceOf(ProcessOutcome.Nack::class.java)
        val nack = result as ProcessOutcome.Nack
        assertThat(nack.retryable).isTrue()
    }
}
