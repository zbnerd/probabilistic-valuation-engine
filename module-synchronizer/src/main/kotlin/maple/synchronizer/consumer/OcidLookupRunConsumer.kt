package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryContext
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.service.OcidLookupService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class OcidLookupRunConsumer(
    private val ocidLookupService: OcidLookupService,
    private val objectMapper: ObjectMapper,
    // Workload-local platform pool decouples Kafka polling from JSON and ingestion work.
    @Qualifier("synchronizerOcidLookupExecutor") private val executor: Executor,
) {
    fun consume(
        message: String,
        context: DeliveryContext,
    ): CompletionStage<DeliveryOutcome> = runCatching {
        CompletableFuture.supplyAsync(
            { objectMapper.readValue(message, SnapshotRunCompletedEvent::class.java) },
            executor,
        ).thenApply { event ->
            if (event.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                DeliveryOutcome.InvalidMessage(UNSUPPORTED_SCHEMA_VERSION)
            } else {
                ocidLookupService.ingest(event)
                DeliveryOutcome.Success
            }
        }
            .handle { outcome, failure ->
                if (failure == null) {
                    requireNotNull(outcome)
                } else {
                    DeliveryOutcome.Retryable(CompletionFailures.unwrap(failure))
                }
            }
    }.getOrElse {
        CompletableFuture.completedFuture(DeliveryOutcome.Backpressure(EXECUTOR_BACKPRESSURE))
    }

    private companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
        private val EXECUTOR_BACKPRESSURE = java.time.Duration.ofSeconds(1)
    }
}
