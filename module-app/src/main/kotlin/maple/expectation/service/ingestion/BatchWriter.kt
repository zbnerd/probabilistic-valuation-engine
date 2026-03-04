package maple.expectation.service.ingestion

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.MessageQueue
import maple.expectation.core.domain.event.IntegrationEvent
import maple.expectation.domain.nexon.NexonApiCharacterData
import maple.expectation.infrastructure.config.BatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.NexonCharacterRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Batch writer for consuming from queue and writing to database.
 *
 * **Stage 3 (Storage) - Anti-Corruption Layer:**
 * - Consumes from [MessageQueue] (decoupled from collector)
 * - Accumulates to batch size (1000 records)
 * - Uses JDBC batch update via repository
 *
 * **Backpressure Control:** Queue acts as a buffer between collector and writer:
 * - Collector can publish faster than writer can process
 * - Queue absorbs spikes in traffic
 * - Writer processes at its own pace (steady state)
 */
@Component
class BatchWriter(
    @Qualifier("nexonDataQueue") private val messageQueue: MessageQueue<String>,
    private val repository: NexonCharacterRepository,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
    private val batchProperties: BatchProperties,
) {

    companion object {
        private val log = LoggerFactory.getLogger(BatchWriter::class.java)
    }

    /**
     * Scheduled batch processing (runs every 5 seconds).
     *
     * **Transactional:** Entire batch is atomic (all or nothing).
     */
    @Scheduled(fixedRate = 5000)
    @Transactional
    fun processBatch() {
        val context = TaskContext.of("BatchWriter", "ProcessBatch")

        executor.executeVoidJava(
            Runnable {
                val batch = ArrayList<IntegrationEvent<NexonApiCharacterData>>()
                val aclWriterSize = batchProperties.aclWriterSize

                // Accumulate batch from queue (JSON strings)
                for (i in 0 until aclWriterSize) {
                    val jsonPayload = messageQueue.poll() ?: break // Queue empty

                    // Deserialize JSON back to IntegrationEvent (with recovery)
                    val event = deserializeEvent(jsonPayload)
                    if (event != null) {
                        batch.add(event)
                    }
                }

                if (batch.isEmpty()) {
                    log.debug("[BatchWriter] No messages to process")
                    return@Runnable // No-op if queue is empty
                }

                // Batch write to database
                batchWrite(batch)

                log.info("[BatchWriter] Processed batch: {} records", batch.size)
            },
            context,
        )
    }

    /**
     * Deserialize JSON payload to IntegrationEvent with error handling.
     *
     * @param jsonPayload JSON string to deserialize
     * @return Deserialized event, or null if parsing fails
     */
    private fun deserializeEvent(jsonPayload: String): IntegrationEvent<NexonApiCharacterData>? {
        val truncatedPayload = jsonPayload.take(50)
        return executor.executeOrDefault(
            {
                objectMapper.readValue(
                    jsonPayload,
                    object : TypeReference<IntegrationEvent<NexonApiCharacterData>>() {},
                )
            },
            null,
            TaskContext.of("BatchWriter", "DeserializeEvent", truncatedPayload),
        )
    }

    /**
     * Batch write to database using repository.
     *
     * @param batch Events to write
     */
    private fun batchWrite(batch: List<IntegrationEvent<NexonApiCharacterData>>) {
        // Extract payloads from IntegrationEvent wrapper
        val dataList = batch.map { it.payload }

        // Repository batch upsert (uses JdbcTemplate.batchUpdate internally)
        repository.batchUpsert(dataList)

        log.debug("[BatchWriter] Batch upsert completed: {} records", dataList.size)
    }
}
