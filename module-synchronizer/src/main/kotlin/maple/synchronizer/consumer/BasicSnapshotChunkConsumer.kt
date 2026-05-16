package maple.synchronizer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.synchronizer.repository.CharacterBasicRepository
import maple.synchronizer.repository.SynchronizerChunkStatusRepository
import maple.synchronizer.storage.BasicChunkFileReader
import maple.synchronizer.storage.BasicRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Component
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val chunkStatusRepository: SynchronizerChunkStatusRepository,
    private val logicExecutor: LogicExecutor,
    private val jdbc: NamedParameterJdbcTemplate,
    @Value("\${synchronizer.store.base-path:../module-external-api/external-api-data}")
    private val basePath: String,
) {
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

        if (chunkStatusRepository.isAlreadySuccess(runId, chunkId)) {
            log.info("[BasicSync] skip already-successful chunk: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey)) {
            log.info("[BasicSync] skip - chunk already claimed: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!processingPermit.tryAcquire()) {
            log.info("[BasicSync] processing permit busy, will retry: runId={} chunkId={}", runId, chunkId)
            return
        }

        MDC.put("runId", runId)
        MDC.put("chunkId", chunkId)

        CompletableFuture.runAsync({
            logicExecutor.executeWithFinally(
                task = {
                    logicExecutor.executeOrCatch(
                        task = {
                            val records = fileReader.read(event.objectKey)
                            repository.bulkUpsert(runId, chunkId, records)
                            chunkStatusRepository.markSuccess(runId, chunkId)
                            log.info("[BasicSync] chunk processed: runId={} chunkId={} records={}",
                                runId, chunkId, records.size)
                            acknowledgment.acknowledge()
                        },
                        recovery = { ex ->
                            chunkStatusRepository.markFailed(runId, chunkId, ex.message ?: "unknown")
                            log.error("[BasicSync] chunk processing failed: runId={} chunkId={}",
                                runId, chunkId, ex)
                            null
                        },
                        context = TaskContext.of("BasicSync", "ChunkProcess", chunkId),
                    )
                },
                finallyBlock = {
                    processingPermit.release()
                    MDC.clear()
                },
                context = TaskContext.of("BasicSync", "ChunkLifecycle", chunkId),
            )
        }, vtExecutor)
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

        if (chunkStatusRepository.isAlreadySuccess(runId, chunkId)) {
            log.info("[BasicSync] skip already-successful urgent chunk: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!chunkStatusRepository.claimChunk(runId, chunkId, event.objectKey)) {
            log.info("[BasicSync] skip - urgent chunk already claimed: runId={} chunkId={}", runId, chunkId)
            acknowledgment.acknowledge()
            return
        }

        if (!processingPermit.tryAcquire()) {
            log.info("[BasicSync] processing permit busy, urgent will retry: runId={} chunkId={}", runId, chunkId)
            return
        }

        MDC.put("runId", runId)
        MDC.put("chunkId", chunkId)

        CompletableFuture.runAsync({
            logicExecutor.executeWithFinally(
                task = {
                    logicExecutor.executeOrCatch(
                        task = {
                            val records = fileReader.read(event.objectKey)
                            repository.bulkUpsert(runId, chunkId, records)
                            chunkStatusRepository.markSuccess(runId, chunkId)

                            upsertOcidFromBasicRecords(records)

                            log.info("[BasicSync] urgent chunk processed: runId={} chunkId={} records={}",
                                runId, chunkId, records.size)
                            acknowledgment.acknowledge()
                        },
                        recovery = { ex ->
                            chunkStatusRepository.markFailed(runId, chunkId, ex.message ?: "unknown")
                            log.error("[BasicSync] urgent chunk processing failed: runId={} chunkId={}",
                                runId, chunkId, ex)
                            null
                        },
                        context = TaskContext.of("BasicSync", "UrgentChunkProcess", chunkId),
                    )
                },
                finallyBlock = {
                    processingPermit.release()
                    MDC.clear()
                },
                context = TaskContext.of("BasicSync", "UrgentChunkLifecycle", chunkId),
            )
        }, vtExecutor)
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
            log.info("[BasicSync] upserted OCID to game_character: userIgn={}", record.userIgn)
        }
    }

    @PreDestroy
    fun close() {
        vtExecutor.close()
    }
}

private data class SnapshotChunkReadyEvent(
    val runId: String,
    val endpoint: String,
    val chunkId: String,
    val objectKey: String,
    val recordCount: Int,
)
