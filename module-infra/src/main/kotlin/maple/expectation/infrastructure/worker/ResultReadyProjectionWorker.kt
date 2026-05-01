package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.zip.GZIPInputStream
import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
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
    private val jobPort: CalculationJobPort,
    private val resultPort: CalculationResultPort,
    private val viewQueryPort: CharacterViewQueryPort,
    private val executor: LogicExecutor,
    private val objectMapper: ObjectMapper,
    @Value("\${pgmq.worker.result-projection.batch-size:100}") private val batchSize: Int,
    @Value("\${pgmq.worker.result-projection.visibility-timeout-sec:30}") private val visibilityTimeoutSec: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${pgmq.worker.result-projection.polling-interval-ms:300}",
        initialDelayString = "\${pgmq.worker.result-projection.initial-delay-ms:5000}",
    )
    fun project() {
        val messages = pgmqClient.read(
            QueueNames.RESULT_READY,
            Map::class.java,
            batchSize = batchSize,
            visibilityTimeoutSec = visibilityTimeoutSec,
        )
        if (messages.isEmpty()) return

        val context = TaskContext.of("ResultProjection", "ProjectBatch", messages.size.toString())
        executor.executeVoid({ projectBatch(messages) }, context)
    }

    private fun projectBatch(messages: List<PgmqMessage<Map<*, *>>>) {
        val archiveIds = mutableListOf<Long>()
        val parsed = messages.mapNotNull { parseMessage(it, archiveIds) }
        if (parsed.isEmpty()) {
            archiveIfNeeded(archiveIds)
            return
        }

        val jobIds = parsed.map { it.jobId }.distinct()
        val jobsById = jobPort.findJobsByIds(jobIds).associateBy { it.jobId }
        val resultsByJobId = resultPort.findByJobIds(jobIds).associateBy { it.jobId }
        val commands = parsed.mapNotNull { message ->
            val job = jobsById[message.jobId]
            val resultData = resultsByJobId[message.jobId]
            when {
                job == null -> {
                    log.warn("[jobId={}] Job not found, skipping", message.jobId)
                    archiveIds += message.messageId
                    null
                }
                resultData == null -> {
                    log.warn("[jobId={}] Result not found, skipping", message.jobId)
                    archiveIds += message.messageId
                    null
                }
                else -> toProjectionCommand(message, job, resultData, archiveIds)
            }
        }

        if (commands.isNotEmpty()) {
            viewQueryPort.batchUpsertFromCalculations(commands)
        }
        archiveIfNeeded(archiveIds)
        log.debug("[ResultProjection] projected={}, archived={}", commands.size, archiveIds.size)
    }

    private fun parseMessage(message: PgmqMessage<Map<*, *>>, archiveIds: MutableList<Long>): ProjectionMessage? {
        val payload = message.payload
        val jobIdStr = payload["jobId"]?.toString() ?: run {
            log.warn("[msgId={}] Missing jobId, skipping", message.messageId)
            archiveIds += message.messageId
            return null
        }
        val jobId = runCatching { UUID.fromString(jobIdStr) }.getOrNull() ?: run {
            log.warn("[msgId={}] Invalid jobId: {}", message.messageId, jobIdStr)
            archiveIds += message.messageId
            return null
        }
        return ProjectionMessage(message.messageId, payload, jobId)
    }

    private fun toProjectionCommand(
        message: ProjectionMessage,
        job: CalculationJob,
        resultData: CalculationResultData,
        archiveIds: MutableList<Long>,
    ): CharacterViewProjectionCommand? {
        val resultJson = decompress(resultData.responseBody)
        val tree = objectMapper.readTree(resultJson)

        val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: run {
            log.warn("[jobId={}] Missing totalExpectedCost in result", message.jobId)
            archiveIds += message.messageId
            return null
        }
        val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: run {
            log.warn("[jobId={}] Missing maxPresetNo in result", message.jobId)
            archiveIds += message.messageId
            return null
        }
        val presetsNode = tree.get("presets")
        val presetNo = (message.payload["presetNo"] as? Number)?.toInt() ?: 1
        val characterId = message.payload["characterId"]?.toString()
        val presetsJson = if (presetsNode != null) objectMapper.writeValueAsString(presetsNode) else "[]"

        archiveIds += message.messageId
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

    private data class ProjectionMessage(
        val messageId: Long,
        val payload: Map<*, *>,
        val jobId: UUID,
    )
}
