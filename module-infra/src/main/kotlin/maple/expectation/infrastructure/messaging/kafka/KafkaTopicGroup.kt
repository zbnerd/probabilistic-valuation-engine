package maple.expectation.infrastructure.messaging.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.core.port.out.mq.MQTopicGroup
import maple.expectation.core.port.out.mq.MessageHandle
import maple.expectation.infrastructure.config.KafkaPipelineProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.KafkaOutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

abstract class KafkaTopicGroup(
    protected val outboxRepository: KafkaOutboxEventRepository,
    protected val objectMapper: ObjectMapper,
    protected val executor: LogicExecutor,
    protected val kafkaTemplate: KafkaTemplate<String, String>,
    protected val properties: KafkaPipelineProperties,
) : MQTopicGroup {

    private val log = LoggerFactory.getLogger(javaClass)
    private val handlerRef = AtomicReference<(IntegrationEvent<*>, MessageHandle) -> ConsumeResult>()

    override fun publish(message: IntegrationEvent<*>): MessageHandle {
        val context = TaskContext.of("KafkaTopic", "Publish", name)
        return executor.execute({
            val eventId = UUID.randomUUID()
            val payload = objectMapper.writeValueAsString(message)
            val key = message.jobId ?: message.eventId

            outboxRepository.insertIfAbsent(
                id = eventId,
                eventType = message.eventType,
                aggregateId = UUID.fromString(message.jobId ?: message.eventId),
                aggregateType = "calculation_job",
                topic = name,
                partitionKey = key,
                payload = payload,
            )

            MessageHandle(id = eventId, raw = eventId)
        }, context)
    }

    override fun subscribe(handler: (IntegrationEvent<*>, MessageHandle) -> ConsumeResult) {
        handlerRef.set(handler)
    }

    fun getHandler(): ((IntegrationEvent<*>, MessageHandle) -> ConsumeResult)? = handlerRef.get()

    fun clearHandler() {
        handlerRef.set(null)
    }

    fun sendToDlt(
        payload: String,
        originalTopic: String,
        partition: Int,
        offset: Long,
        consumerGroup: String,
        errorType: String,
        errorMessage: String,
    ) {
        log.warn(
            "[KafkaTopicGroup] DLT: errorType={}, message={}, topic={}[{}]",
            errorType,
            errorMessage,
            originalTopic,
            partition,
        )

        val dltPayload = mapOf(
            "originalTopic" to originalTopic,
            "originalPartition" to partition,
            "originalOffset" to offset,
            "consumerGroup" to consumerGroup,
            "errorType" to errorType,
            "errorMessage" to errorMessage,
            "payload" to payload,
            "failedAt" to java.time.Instant.now().toString(),
        )

        kafkaTemplate.send(
            dltTopicName,
            objectMapper.writeValueAsString(dltPayload),
        )
    }

    abstract val dltTopicName: String
    abstract val consumerGroup: String
}
