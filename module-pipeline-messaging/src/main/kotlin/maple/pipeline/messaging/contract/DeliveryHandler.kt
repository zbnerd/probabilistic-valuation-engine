package maple.pipeline.messaging.contract

import java.util.concurrent.CompletionStage

fun interface DeliveryHandler {
    fun handle(payload: String, context: DeliveryContext): CompletionStage<DeliveryOutcome>
}
