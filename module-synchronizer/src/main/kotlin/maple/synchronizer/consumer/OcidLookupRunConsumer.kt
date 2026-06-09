package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.ExecutorService

@Component
@ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
class OcidLookupRunConsumer(
    private val ocidLookupService: OcidLookupService,
    private val objectMapper: ObjectMapper,
    // Issue #1129: dispatch to executor (default async, post-#1126 rename). Decouples Kafka poll from processing.
    @Qualifier("defaultAsyncExecutor") private val executor: ExecutorService,
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
        executor.submit {
            runCatching {
                // CPU offload: JSON parse on Dispatchers.Default.
                val event = runBlocking(Dispatchers.Default) {
                    objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java)
                }
                // IO (ingest) on caller thread.
                ocidLookupService.ingest(event)
            }.onFailure { ex ->
                logger.error("[OcidLookupRun] consume failed", ex)
            }
            runCatching { acknowledgment.acknowledge() }
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(OcidLookupRunConsumer::class.java)
    }
}
