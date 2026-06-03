package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMappingFileReader
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
class OcidLookupRunConsumer(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.ocid-lookup-topic}"],
        groupId = "\${synchronizer.kafka.ocid-lookup-consumer-group-id}",
    )
    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        val event = objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java)
        if (event.endpoint != "ocid-lookup") {
            acknowledgment.acknowledge()
            return
        }

        log.info("[OcidConsumer] received: runId={} totalRecords={} manifestPath={}",
            event.runId, event.totalRecords, event.manifestPath)

        val mappings = fileReader.read(event.manifestPath)
        if (mappings.isEmpty()) {
            log.warn("[OcidConsumer] no mappings found in: {}", event.manifestPath)
            acknowledgment.acknowledge()
            return
        }

        repository.batchUpsert(mappings)
        runCatching {
            repository.writeOcidToRedis(mappings)
        }.onFailure { ex ->
            log.error(
                "[OcidConsumer] Redis write failed after DB upsert: runId={} mappings={} - {}. Redis may be stale until next run.",
                event.runId, mappings.size, ex.message, ex,
            )
        }

        log.info("[OcidConsumer] completed: runId={} processed={}", event.runId, mappings.size)
        acknowledgment.acknowledge()
    }
}
