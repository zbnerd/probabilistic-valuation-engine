package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.SynchronizerChunkStatusRepository
import maple.synchronizer.storage.BasicChunkFileReader
import maple.synchronizer.storage.BasicRecord
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val chunkStatusRepository: SynchronizerChunkStatusRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    @Value("\${synchronizer.store.base-path:../module-external-api/external-api-data}")
    private val basePath: String,
	) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()
    private val processingPermit = Semaphore(2)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.basic-consumer-group-id}",
    )
    fun consume(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)

        if (event.endpoint != "character-basic") return

        val runId = event.runId
        val chunkId = event.chunkId

        log.info("[BasicSync] received: runId={} chunkId={} objectKey={} records={}",
            runId, chunkId, event.objectKey, event.recordCount)

        submitBasicChunk(event, acknowledgment, urgent = false)
    }

    @KafkaListener(
        topics = ["\${synchronizer.kafka.urgent-basic-chunk-ready-topic}"],
        groupId = "\${synchronizer.kafka.urgent-basic-consumer-group-id}",
    )
    fun consumeUrgentBasic(message: String, acknowledgment: Acknowledgment) {
        val event = objectMapper.readValue(message, SnapshotChunkReadyEvent::class.java)

        if (event.endpoint != "character-basic") {
            acknowledgment.acknowledge()
            return
        }

        val runId = event.runId
        val chunkId = event.chunkId

        log.info("[BasicSync] received URGENT: runId={} chunkId={} objectKey={} records={}",
            runId, chunkId, event.objectKey, event.recordCount)

        submitBasicChunk(event, acknowledgment, urgent = true)
    }

    private fun submitBasicChunk(
        event: SnapshotChunkReadyEvent,
        acknowledgment: Acknowledgment,
        urgent: Boolean,
    ) {
        val runId = event.runId
        val chunkId = event.chunkId
        val operation = if (urgent) "UrgentChunk" else "Chunk"

        chunkConsumerTemplate.submit(
            ChunkConsumerRequest(
                logPrefix = "BasicSync",
                log = log,
                runId = runId,
                chunkId = chunkId,
                objectKey = event.objectKey,
                acknowledgment = acknowledgment,
                processingPermit = processingPermit,
                executor = vtExecutor,
                processContext = TaskContext.of("BasicSync", "${operation}Process", chunkId),
                lifecycleContext = TaskContext.of("BasicSync", "${operation}Lifecycle", chunkId),
                isAlreadySuccess = { chunkStatusRepository.isAlreadySuccess(runId, chunkId) },
                claimChunk = { chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey) },
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
                markSuccess = { chunkStatusRepository.markSuccess(runId, chunkId) },
                markFailed = { reason -> chunkStatusRepository.markFailed(runId, chunkId, reason) },
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
                """INSERT INTO game_character (user_ign, ocid, created_at, updated_at)
                   VALUES (:userIgn, :ocid, NOW(), NOW())
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
