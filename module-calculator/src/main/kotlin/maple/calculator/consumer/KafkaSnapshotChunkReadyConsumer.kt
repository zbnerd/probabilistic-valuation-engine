package maple.calculator.consumer

import maple.calculator.parser.SnapshotEventParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import maple.pipeline.messaging.contract.DeliveryOutcome
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class KafkaSnapshotChunkReadyConsumer(
    private val eventParser: SnapshotEventParser,
    private val dispatchService: SnapshotDispatchService,
) {
    private val log = LoggerFactory.getLogger(KafkaSnapshotChunkReadyConsumer::class.java)

    fun consume(message: String): CompletionStage<DeliveryOutcome> = handle(message, "Consumer")

    fun consumeUrgent(message: String): CompletionStage<DeliveryOutcome> = handle(message, "URGENT")

    private fun handle(message: String, label: String): CompletionStage<DeliveryOutcome> = runCatching {
        eventParser.parse(message)
    }.fold(
        onSuccess = { event -> dispatch(event, label) },
        onFailure = { CompletableFuture.completedFuture(DeliveryOutcome.InvalidMessage("INVALID_MESSAGE")) },
    )

    private fun dispatch(
        event: maple.expectation.common.event.SnapshotChunkReadyEvent,
        label: String,
    ): CompletionStage<DeliveryOutcome> {
        log.info(
            "[{}] received chunk-ready: runId={} endpoint={} chunkId={} objectKey={} recordCount={}",
            label,
            event.runId,
            event.endpoint,
            event.chunkId,
            event.objectKey,
            event.recordCount,
        )
        return dispatchService.dispatch(event, label)
    }
}
