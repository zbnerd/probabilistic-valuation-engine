package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class ResultReadyTopic(
    pgmqClient: PgmqClient,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    queueMetrics: WorkerQueueMetrics,
) : PgmqTopicGroup(
    pgmqClient,
    objectMapper,
    executor,
    lifecycleWrapper,
    queueMetrics,
    PgmqTopicConfig(batchSize = 10, visibilityTimeoutSec = 30),
) {
    override val name: String = QueueNames.RESULT_READY
}
