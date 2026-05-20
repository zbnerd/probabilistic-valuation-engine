package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.ChunkExecutionIdentity
import maple.expectation.common.event.ChunkExecutionType
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.storage.BasicChunkFileReader
import maple.synchronizer.storage.BasicRecord
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val processingPermit = Semaphore(2)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.basic-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)

        if (event.endpoint != "character-basic") {
            acknowledgment.acknowledge()
            return
        }

        val runId = event.runId
        val chunkId = event.chunkId

        log.info("[BasicSync] received: runId={} chunkId={} objectKey={} records={}",
            runId, chunkId, event.objectKey, event.recordCount)

        submitBasicChunk(event, message, acknowledgment, topic, messageKey, urgent = false)
    }

    @KafkaListener(
        topics = ["\${synchronizer.kafka.urgent-basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.urgent-basic-consumer-group-id}",
    )
    fun consumeUrgentBasic(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) messageKey: String?,
    ) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)

        if (event.endpoint != "character-basic") {
            acknowledgment.acknowledge()
            return
        }

        val runId = event.runId
        val chunkId = event.chunkId

        log.info("[BasicSync] received URGENT: runId={} chunkId={} objectKey={} records={}",
            runId, chunkId, event.objectKey, event.recordCount)

        submitBasicChunk(event, message, acknowledgment, topic, messageKey, urgent = true)
    }

    private fun submitBasicChunk(
        event: SnapshotChunkReadyEvent,
        eventPayloadJson: String,
        acknowledgment: Acknowledgment,
        topic: String?,
        messageKey: String?,
        urgent: Boolean,
    ) {
        val runId = event.runId
        val chunkId = event.chunkId
        val operation = if (urgent) "UrgentChunk" else "Chunk"
        val identity = ChunkExecutionIdentity(
            executionType = ChunkExecutionType.SYNCHRONIZER_BASIC_CHUNK,
            runId = runId,
            endpoint = event.endpoint,
            chunkId = chunkId,
        )

        chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                logPrefix = "BasicSync",
                log = log,
                identity = identity,
                topic = topic ?: event.eventType,
                messageKey = messageKey ?: event.kafkaKey(),
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventPayloadJson = eventPayloadJson,
                objectKey = event.objectKey,
                acknowledgment = acknowledgment,
                processingPermit = processingPermit,
                executor = vtExecutor,
                processContext = TaskContext.of("BasicSync", "${operation}Process", chunkId),
                lifecycleContext = TaskContext.of("BasicSync", "${operation}Lifecycle", chunkId),
                process = {
                    val records = fileReader.read(event.objectKey)
                    repository.bulkUpsert(runId, chunkId, records)
                    if (urgent) {
                        upsertOcidFromBasicRecords(records)
                    }
                    log.info(
                        "[BasicSync] {}chunk processed: runId={} chunkId={} records={}",
                        if (urgent) "urgent " else "",
                        runId,
                        chunkId,
                        records.size,
                    )
                },
                onFailure = { ex ->
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
        records.forEach { record ->
            jdbc.update(
                """INSERT INTO game_character (user_ign, ocid, updated_at)
                   VALUES (:userIgn, :ocid, NOW())
                   ON CONFLICT (user_ign) DO UPDATE SET ocid = EXCLUDED.ocid, updated_at = NOW()""",
                MapSqlParameterSource()
                    .addValue("userIgn", record.userIgn)
                    .addValue("ocid", record.ocid)
            )
            log.info("[BasicSync] upserted OCID to game_character: userIgn={}", maskIgn(record.userIgn))
        }
    }

    override val lifecyclePhase: Int = 100

    override fun stopLifecycle() {
        vtExecutor.close()
    }
}
