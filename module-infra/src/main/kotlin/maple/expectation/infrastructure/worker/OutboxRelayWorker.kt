package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.mq.event.ResultReadyEventFactory
import maple.expectation.infrastructure.mq.pgmq.topic.ResultReadyTopic
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxRelayWorker(
    private val outboxPort: OutboxEventPort,
    private val resultReadyTopic: ResultReadyTopic,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    @Transactional
    fun relay() {
        val events = outboxPort.findUnpublished(50)
        if (events.isEmpty()) return

        if (events.size >= 10) {
            log.info("Relaying {} outbox events", events.size)
        }

        for (event in events) {
            val context = TaskContext.of("OutboxRelayWorker", "Relay", event.eventId.toString())
            executor.executeOrCatch(
                {
                    val payload = event.payload?.let {
                        runCatching { objectMapper.readValue(it, Map::class.java) as Map<*, *> }.getOrNull()
                    }
                    val integrationEvent = ResultReadyEventFactory.create(
                        jobId = event.jobId.toString(),
                        resultId = event.eventId.toString(),
                        characterId = payload?.get("characterId")?.toString() ?: "",
                        presetNo = (payload?.get("presetNo") as? Number)?.toInt() ?: 1,
                    )
                    resultReadyTopic.publish(integrationEvent)
                    outboxPort.markPublished(event.eventId)
                },
                { e ->
                    log.warn("[eventId={}] Publish failed: {}", event.eventId, e.message)
                    outboxPort.incrementPublishAttempts(event.eventId)
                },
                context,
            )
        }
    }
}
