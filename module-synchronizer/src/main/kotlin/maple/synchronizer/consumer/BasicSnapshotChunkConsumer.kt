package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.service.BasicChunkIngestionService
import org.springframework.stereotype.Component

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val ingestionService: BasicChunkIngestionService,
) {
    fun consume(
        message: String,
        context: DeliveryContext,
    ): CompletionStage<DeliveryOutcome> = handle(message, context, urgent = false)

    fun consumeUrgentBasic(
        message: String,
        context: DeliveryContext,
    ): CompletionStage<DeliveryOutcome> = handle(message, context, urgent = true)

    private fun handle(
        message: String,
        context: DeliveryContext,
        urgent: Boolean,
    ): CompletionStage<DeliveryOutcome> {
        val event = runCatching {
            objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)
        }.getOrElse {
            return CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage(INVALID_MESSAGE))
        }
        return ingestionService.process(
            event = event,
            eventPayloadJson = message,
            topic = context.topic,
            messageKey = context.key,
            urgent = urgent,
        )
    }

    private companion object {
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
    }
}
