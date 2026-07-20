package maple.calculator.consumer

import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import maple.calculator.CalculatorChunkProcessingCoordinator
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.pipeline.messaging.contract.CompletionFailures
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** One calculator attempt. Technical retry and ACK are owned by pipeline messaging. */
@Service
class SnapshotDispatchService(
    private val coordinator: CalculatorChunkProcessingCoordinator,
    @Qualifier("vtDispatcher") private val calculatorDispatcher: CoroutineDispatcher,
) {
    private val log = LoggerFactory.getLogger(SnapshotDispatchService::class.java)

    fun dispatch(event: SnapshotChunkReadyEvent, label: String): CompletionStage<DeliveryOutcome> =
        CoroutineScope(calculatorDispatcher)
            .future { coordinator.handle(event) }
            .handle { _, failure -> mapOutcome(event, label, failure) }

    private fun mapOutcome(
        event: SnapshotChunkReadyEvent,
        label: String,
        failure: Throwable?,
    ): DeliveryOutcome = if (failure == null) {
        DeliveryOutcome.Success
    } else {
        log.warn("[{}] dispatch attempt failed: runId={} chunkId={}", label, event.runId, event.chunkId)
        DeliveryOutcome.Retryable(CompletionFailures.unwrap(failure))
    }
}
