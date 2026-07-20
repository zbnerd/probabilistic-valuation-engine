package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.service.OcidLookupService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class OcidLookupRunConsumer(
    private val ocidLookupService: OcidLookupService,
    private val objectMapper: ObjectMapper,
    // Issue #1129: dispatch to executor (default async, post-#1126 rename). Decouples Kafka poll from processing.
    @Qualifier("defaultAsyncExecutor") private val executor: Executor,
) {
    fun consume(
        message: String,
        context: DeliveryContext,
    ): CompletionStage<DeliveryOutcome> = runCatching {
        CompletableFuture.supplyAsync(
            {
                val event = runCatching {
                    objectMapper.readValue(message, SnapshotRunCompletedEvent::class.java)
                }.getOrElse {
                    return@supplyAsync DeliveryOutcome.InvalidMessage(INVALID_MESSAGE)
                }
                if (event.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                    DeliveryOutcome.InvalidMessage(UNSUPPORTED_SCHEMA_VERSION)
                } else {
                    runCatching { ocidLookupService.ingest(event) }.fold(
                        onSuccess = { DeliveryOutcome.Success },
                        onFailure = { failure -> DeliveryOutcome.Retryable(failure) },
                    )
                }
            },
            executor,
        )
    }.getOrElse {
        CompletableFuture.completedFuture(DeliveryOutcome.Backpressure(EXECUTOR_BACKPRESSURE))
    }

    private companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val INVALID_MESSAGE = "INVALID_MESSAGE"
        private const val UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
        private val EXECUTOR_BACKPRESSURE = java.time.Duration.ofSeconds(1)
    }
}
