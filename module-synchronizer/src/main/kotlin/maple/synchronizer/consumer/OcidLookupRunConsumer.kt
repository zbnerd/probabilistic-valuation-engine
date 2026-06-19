package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.service.OcidLookupService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

@Component
@ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
class OcidLookupRunConsumer(
    private val ocidLookupService: OcidLookupService,
    private val objectMapper: ObjectMapper,
    // Issue #1129: dispatch to executor (default async, post-#1126 rename). Decouples Kafka poll from processing.
    @Qualifier("defaultAsyncExecutor") private val executor: Executor,
) {
    @KafkaListener(
        topics = ["\${synchronizer.kafka.ocid-lookup-topic}"],
        groupId = "\${synchronizer.kafka.ocid-lookup-consumer-group-id}",
    )
    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        // CPU offload: JSON parse via CompletableFuture.supplyAsync on the executor —
        // replaces the prior runBlocking(Dispatchers.Default) coroutine bridge.
        CompletableFuture
            .supplyAsync(
                { objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java) },
                executor,
            ).thenAccept { event -> ocidLookupService.ingest(event) }
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.error("[OcidLookupRun] consume failed", ex)
                }
                runCatching { acknowledgment.acknowledge() }
            }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(OcidLookupRunConsumer::class.java)
    }
}
