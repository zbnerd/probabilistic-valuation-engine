package maple.synchronizer.service

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.port.out.ChunkFileReaderPort
import maple.pipeline.messaging.contract.DeliveryOutcome
import maple.synchronizer.consumer.ChunkConsumerRequest
import maple.synchronizer.consumer.ChunkConsumerTemplate
import maple.synchronizer.domain.BasicRecord
import maple.synchronizer.domain.OcidMapping
import maple.synchronizer.event.KafkaChunkConsumedEventPublisher
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.OcidMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class BasicChunkIngestionService(
    private val chunkFileReader: ChunkFileReaderPort,
    private val repository: CharacterBasicRepository,
    private val ocidMappingRepository: OcidMappingRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
    @Qualifier("basicSnapshotChunkExecutor") private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val processingPermit = Semaphore(2)

    fun process(
        event: SnapshotChunkReadyEvent,
        eventPayloadJson: String,
        topic: String?,
        messageKey: String?,
        urgent: Boolean,
    ): CompletionStage<DeliveryOutcome> {
        if (event.endpoint != "character-basic") {
            return CompletableFuture.completedFuture(DeliveryOutcome.TerminalDrop(ENDPOINT_MISMATCH))
        }

        val runId = event.runId
        val chunkId = event.chunkId
        val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_BASIC_CHUNK,
            runId = runId,
            endpoint = event.endpoint,
            chunkId = chunkId,
        )

        return chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                identity = identity,
                topic = topic ?: event.eventType,
                messageKey = messageKey ?: event.kafkaKey(),
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventPayloadJson = eventPayloadJson,
                processingPermit = processingPermit,
                executor = executor,
                process = {
                    var totalRecords = 0
                    chunkFileReader.readBasicChunk(event.objectKey)
                        .chunked(BATCH_SIZE)
                        .forEach { batch ->
                            repository.bulkUpsert(runId, chunkId, batch)
                            if (urgent) {
                                upsertOcidFromBasicRecords(batch)
                            }
                            totalRecords += batch.size
                        }
                    log.info(
                        "[BasicSync] {}chunk processed: runId={} chunkId={} records={}",
                        if (urgent) "urgent " else "",
                        runId,
                        chunkId,
                        totalRecords,
                    )
                },
                publishRequired = {
                    consumedEventPublisher.publish(
                        ChunkConsumedEvent(
                            runId = runId,
                            endpoint = event.endpoint,
                            chunkId = chunkId,
                            objectKey = event.objectKey,
                        ),
                    )
                },
                onObservedFailure = { ex ->
                    log.error(
                        "[BasicSync] {}chunk processing failed: runId={} chunkId={}",
                        if (urgent) "urgent " else "",
                        runId,
                        chunkId,
                        ex,
                    )
                },
            ),
        )
    }

    private fun upsertOcidFromBasicRecords(records: List<BasicRecord>) {
        val mappings = records.map { OcidMapping(userIgn = it.userIgn, ocid = it.ocid) }
        ocidMappingRepository.batchUpsert(mappings)
        log.info("[BasicSync] batch upserted OCID mappings: count={}", mappings.size)
    }

    companion object {
        private const val BATCH_SIZE = 1000
        private const val ENDPOINT_MISMATCH = "ENDPOINT_MISMATCH"
    }
}
