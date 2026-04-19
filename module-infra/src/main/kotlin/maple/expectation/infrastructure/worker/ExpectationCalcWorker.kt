package maple.expectation.infrastructure.worker

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Executor
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!test")
class ExpectationCalcWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    expectationPort: ExpectationV4Port,
    characterOcidPort: CharacterOcidPort,
    equipmentFanOutPort: EquipmentFanOutPort,
    @Qualifier("asyncExecutor") preWarmExecutor: Executor,
) : AbstractExpectationCalcWorker(
    pgmqClient,
    executor,
    config,
    meterRegistry,
    queueMetrics,
    lifecycleWrapper,
    expectationPort,
    characterOcidPort,
    equipmentFanOutPort,
    preWarmExecutor,
) {

    override val queueName: String = QUEUE_NAME
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.expectationCalcHigh
    override val workerName: String = "ExpectationCalcWorker"
    override val workerLog: Logger = log

    companion object {
        private val log = LoggerFactory.getLogger(ExpectationCalcWorker::class.java)
        const val QUEUE_NAME = "expectation_calc_high"
    }
}
