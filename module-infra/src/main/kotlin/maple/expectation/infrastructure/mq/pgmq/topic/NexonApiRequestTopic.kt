package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class NexonApiRequestTopic(
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
    PgmqTopicConfig(batchSize = 5, visibilityTimeoutSec = 120),
) {
    override val name: String = "nexon_api_request_queue"
}
