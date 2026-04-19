package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.Logger

abstract class AbstractExpectationCalcWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val expectationPort: ExpectationV4Port,
    private val characterOcidPort: CharacterOcidPort,
    private val equipmentFanOutPort: EquipmentFanOutPort,
    private val preWarmExecutor: Executor,
) : PgmqWorker<ExpectationCalcMessage>(pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java

    protected abstract val workerName: String
    protected abstract val workerLog: Logger

    override fun preWarmBatch(messages: List<PgmqMessage<ExpectationCalcMessage>>) {
        val context = TaskContext.of(workerName, "PreWarm", queueName)

        executor.executeVoid({
            val igns = messages.asSequence().map { it.payload.userIgn }.toSet()
            if (igns.isEmpty()) return@executeVoid

            val ignToOcid = characterOcidPort.resolveOcids(igns)
            if (ignToOcid.isEmpty()) return@executeVoid

            val warmupFutures = ignToOcid.values.map { ocid ->
                CompletableFuture.supplyAsync(
                    { equipmentFanOutPort.preFetchByOcid(ocid) },
                    preWarmExecutor,
                )
            }

            CompletableFuture.allOf(*warmupFutures.toTypedArray())
                .orTimeout(15, TimeUnit.SECONDS)
                .handle { _, _ -> null }
                .join()

            workerLog.info("[{}] Pre-warm: {} igns -> {} ocids", workerName, igns.size, ignToOcid.size)
        }, context)
    }

    override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean {
        val request = message.payload
        val context = TaskContext.of(workerName, "Process", request.userIgn)

        return executor.executeOrDefault({
            workerLog.info("[{}] Processing: userIgn={}, taskId={}", workerName, request.userIgn, message.messageId)

            expectationPort.calculateExpectationAsync(
                request.userIgn,
                request.forceRecalculation,
                message.messageId.toString(),
            ).join()

            workerLog.info("[{}] Completed: userIgn={}, taskId={}", workerName, request.userIgn, message.messageId)
            true
        }, false, context)
    }
}
