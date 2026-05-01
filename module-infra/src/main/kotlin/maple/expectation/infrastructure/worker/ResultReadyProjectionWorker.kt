package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.OutboxEvent
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class ResultReadyProjectionWorker(
    private val pgmqClient: PgmqClient,
    private val outboxPort: OutboxEventPort,
    private val jobPort: CalculationJobPort,
    private val resultPort: CalculationResultPort,
    private val viewQueryPort: CharacterViewQueryPort,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
    @Value("\${pgmq.worker.result-projection.batch-size:100}") private val batchSize: Int,
    @Value("\${pgmq.worker.result-projection.visibility-timeout-sec:30}") private val visibilityTimeoutSec: Int,
    @Value("\${app.pipeline.consolidated.enabled:true}") private val consolidatedEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${pgmq.worker.result-projection.polling-interval-ms:300}",
        initialDelayString = "\${pgmq.worker.result-projection.initial-delay-ms:5000}",
    )
    fun project() {
        if (consolidatedEnabled) {
            projectFromOutbox()
        } else {
            projectFromPgmq()
        }
    }

    // === Consolidated: read outbox directly, no PGMQ hop ===

    private fun projectFromOutbox() {
        val events = outboxPort.findUnpublished(batchSize)
        if (events.isEmpty()) return

        val context = TaskContext.of("ResultProjection", "ProjectBatch", events.size.toString())
        executor.executeVoid({ projectOutboxBatch(events) }, context)
    }

    private fun projectOutboxBatch(events: List<OutboxEvent>) {
        val jobIds = events.map { it.jobId }.distinct()
        val jobsById = jobPort.findJobsByIds(jobIds).associateBy { it.jobId }
        val resultsByJobId = resultPort.findByJobIds(jobIds).associateBy { it.jobId }

        val outcomes = runBlocking(Dispatchers.Default) {
            events.map { event ->
                async(Dispatchers.Default) {
                    val job = jobsById[event.jobId]
                    val resultData = resultsByJobId[event.jobId]
                    if (job == null || resultData == null) {
                        OutboxProjectionOutcome(event.eventId, command = null)
                    } else {
                        val payload = parseOutboxPayload(event)
                        val command = toOutboxProjectionCommand(event, job, resultData, payload)
                        OutboxProjectionOutcome(event.eventId, command = command)
                    }
                }
            }.awaitAll()
        }

        val commands = outcomes.mapNotNull { it.command }
        val publishedIds = outcomes.map { it.eventId }

        if (commands.isNotEmpty()) {
            viewQueryPort.batchUpsertFromCalculations(commands)
        }
        if (publishedIds.isNotEmpty()) {
            outboxPort.markAllPublished(publishedIds)
        }
        log.debug("[ResultProjection] projected={}, published={}", commands.size, publishedIds.size)
    }

    private fun parseOutboxPayload(event: OutboxEvent): Map<*, *> {
        if (event.payload == null) return emptyMap<String, Any>()
        return runCatching { objectMapper.readValue(event.payload, Map::class.java) as Map<*, *> }
            .getOrDefault(emptyMap<String, Any>())
    }

    private fun toOutboxProjectionCommand(
        event: OutboxEvent,
        job: CalculationJob,
        resultData: CalculationResultData,
        payload: Map<*, *>,
    ): CharacterViewProjectionCommand? {
        val resultJson = decompress(resultData.responseBody)
        val tree = objectMapper.readTree(resultJson)

        val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return null
        val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return null
        val presetsNode = tree.get("presets")
        val presetNo = (payload["presetNo"] as? Number)?.toInt() ?: 1
        val characterId = payload["characterId"]?.toString()
        val presetsJson = if (presetsNode != null) objectMapper.writeValueAsString(presetsNode) else "[]"

        return CharacterViewProjectionCommand(
            userIgn = job.userIgn,
            messageId = event.eventId.toString(),
            characterOcid = characterId,
            characterClass = resultData.characterClass,
            characterLevel = null,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            presetNo = presetNo,
            presetsJson = presetsJson,
        )
    }

    // === Legacy: read from PGMQ result_ready_queue ===

    private fun projectFromPgmq() {
        val messages = pgmqClient.read(
            QueueNames.RESULT_READY,
            Map::class.java,
            batchSize = batchSize,
            visibilityTimeoutSec = visibilityTimeoutSec,
        )
        if (messages.isEmpty()) return

        val context = TaskContext.of("ResultProjection", "ProjectBatch", messages.size.toString())
        executor.executeVoid({ projectPgmqBatch(messages) }, context)
    }

    private fun projectPgmqBatch(messages: List<PgmqMessage<Map<*, *>>>) {
        val archiveIds = mutableListOf<Long>()
        val parsed = messages.mapNotNull { parsePgmqMessage(it, archiveIds) }
        if (parsed.isEmpty()) {
            archiveIfNeeded(archiveIds)
            return
        }

        val jobIds = parsed.map { it.jobId }.distinct()
        val jobsById = jobPort.findJobsByIds(jobIds).associateBy { it.jobId }
        val resultsByJobId = resultPort.findByJobIds(jobIds).associateBy { it.jobId }
        val outcomes = buildPgmqProjectionCommands(parsed, jobsById, resultsByJobId)
        val commands = outcomes.mapNotNull { it.command }
        archiveIds += outcomes.filter { it.archive }.map { it.messageId }

        if (commands.isNotEmpty()) {
            viewQueryPort.batchUpsertFromCalculations(commands)
        }
        archiveIfNeeded(archiveIds)
    }

    private fun buildPgmqProjectionCommands(
        parsed: List<PgmqProjectionMessage>,
        jobsById: Map<UUID, CalculationJob>,
        resultsByJobId: Map<UUID, CalculationResultData>,
    ): List<PgmqProjectionOutcome> = runBlocking(Dispatchers.Default) {
        parsed.map { message ->
            async(Dispatchers.Default) {
                val job = jobsById[message.jobId]
                val resultData = resultsByJobId[message.jobId]
                when {
                    job == null || resultData == null -> PgmqProjectionOutcome(message.messageId, archive = true)
                    else -> PgmqProjectionOutcome(
                        messageId = message.messageId,
                        command = toPgmqProjectionCommand(message, job, resultData),
                        archive = true,
                    )
                }
            }
        }.awaitAll()
    }

    private fun parsePgmqMessage(message: PgmqMessage<Map<*, *>>, archiveIds: MutableList<Long>): PgmqProjectionMessage? {
        val payload = message.payload
        val jobIdStr = payload["jobId"]?.toString() ?: run {
            archiveIds += message.messageId
            return null
        }
        val jobId = runCatching { UUID.fromString(jobIdStr) }.getOrNull() ?: run {
            archiveIds += message.messageId
            return null
        }
        return PgmqProjectionMessage(message.messageId, payload, jobId)
    }

    private fun toPgmqProjectionCommand(
        message: PgmqProjectionMessage,
        job: CalculationJob,
        resultData: CalculationResultData,
    ): CharacterViewProjectionCommand? {
        val resultJson = decompress(resultData.responseBody)
        val tree = objectMapper.readTree(resultJson)

        val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return null
        val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return null
        val presetsNode = tree.get("presets")
        val presetNo = (message.payload["presetNo"] as? Number)?.toInt() ?: 1
        val characterId = message.payload["characterId"]?.toString()
        val presetsJson = if (presetsNode != null) objectMapper.writeValueAsString(presetsNode) else "[]"

        return CharacterViewProjectionCommand(
            userIgn = job.userIgn,
            messageId = message.messageId.toString(),
            characterOcid = characterId,
            characterClass = resultData.characterClass,
            characterLevel = null,
            totalExpectedCost = totalExpectedCost,
            maxPresetNo = maxPresetNo,
            presetNo = presetNo,
            presetsJson = presetsJson,
        )
    }

    private fun archiveIfNeeded(messageIds: List<Long>) {
        if (messageIds.isNotEmpty()) {
            pgmqClient.archiveBatch(QueueNames.RESULT_READY, messageIds)
        }
    }

    private fun decompress(data: ByteArray): String {
        GZIPInputStream(data.inputStream()).use { return String(it.readAllBytes()) }
    }

    private data class PgmqProjectionMessage(
        val messageId: Long,
        val payload: Map<*, *>,
        val jobId: UUID,
    )

    private data class PgmqProjectionOutcome(
        val messageId: Long,
        val command: CharacterViewProjectionCommand? = null,
        val archive: Boolean = false,
    )

    private data class OutboxProjectionOutcome(
        val eventId: UUID,
        val command: CharacterViewProjectionCommand? = null,
    )
}
