package maple.cleanup.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.expectation.common.event.ChunkConsumedEvent
import maple.pipeline.artifact.inbox.CleanupInboxEntry
import maple.pipeline.artifact.inbox.CleanupInboxStore
import maple.pipeline.artifact.inbox.InboxPutResult
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.springframework.stereotype.Component

@Component
class ConsumedChunkInbox(
    private val objectMapper: ObjectMapper,
    private val store: CleanupInboxStore,
    private val clock: Clock,
) {
    fun consume(message: String, context: DeliveryContext): CompletionStage<DeliveryOutcome> {
        val event = runCatching {
            objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        }.getOrElse {
            return CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage(INVALID_MESSAGE))
        }
        val entry = CleanupInboxEntry(
            eventId = event.eventId,
            topic = context.topic,
            partition = context.partition,
            offset = context.offset,
            receivedAt = Instant.now(clock),
            event = event,
        )
        val persistence = runCatching { store.putIfAbsent(entry) }.getOrElse { failure ->
            return CompletableFuture.completedFuture(DeliveryOutcome.Retryable(failure))
        }
        return persistence.handle { result, failure ->
            if (failure != null) {
                DeliveryOutcome.Retryable(CompletionFailures.unwrap(failure))
            } else {
                when (result) {
                    InboxPutResult.Created,
                    InboxPutResult.Replay,
                    -> DeliveryOutcome.Success
                    is InboxPutResult.IntegrityConflict -> DeliveryOutcome.InvalidMessage(INBOX_EVENT_ID_CONFLICT)
                    null -> DeliveryOutcome.Retryable(IllegalStateException("cleanup inbox store returned no result"))
                }
            }
        }
    }

    private companion object {
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
        private const val INBOX_EVENT_ID_CONFLICT = "INBOX_EVENT_ID_CONFLICT"
    }
}
