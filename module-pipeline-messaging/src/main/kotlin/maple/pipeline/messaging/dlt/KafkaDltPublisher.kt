package maple.pipeline.messaging.dlt

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import maple.pipeline.messaging.contract.SafeDeliveryException
import org.apache.kafka.clients.consumer.ConsumerRecord

fun interface DltPublisher {
    fun publish(record: ConsumerRecord<String, String>, reason: String, attempt: Int): CompletionStage<Void>
}

class KafkaDltPublisher(
    private val recoverer: SafeDeadLetterPublishingRecoverer,
    private val pipelineDltExecutor: Executor,
) : DltPublisher {
    override fun publish(
        record: ConsumerRecord<String, String>,
        reason: String,
        attempt: Int,
    ): CompletionStage<Void> = CompletableFuture.runAsync(
        { recoverer.accept(record, SafeDeliveryException(reason, attempt)) },
        pipelineDltExecutor,
    )
}
