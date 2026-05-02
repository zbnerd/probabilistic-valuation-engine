package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
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
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.StepTimer
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
    @Qualifier("asyncExecutor") private val asyncExecutor: ExecutorService,
    @Value("\${pgmq.worker.result-projection.batch-size:100}") private val batchSize: Int,
    @Value("\${pgmq.worker.result-projection.visibility-timeout-sec:30}") private val visibilityTimeoutSec: Int,
    @Value("\${app.slow-task.step-trace.threshold-ms:500}") private val stepTraceThresholdMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${pgmq.worker.result-projection.polling-interval-ms:300}",
        initialDelayString = "\${pgmq.worker.result-projection.initial-delay-ms:5000}",
    )
    fun project() {
        projectFromPgmq()
    }

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
        val timer = StepTimer("ResultProjection:ProjectBatch", stepTraceThresholdMs, tags = mapOf("batchSize" to messages.size.toString()))
        try {
            val archiveIds = mutableListOf<Long>()
            val parsed = messages.mapNotNull { parsePgmqMessage(it, archiveIds) }
            timer.mark("parseMessages")
            if (parsed.isEmpty()) {
                archiveIfNeeded(archiveIds)
                return
            }

            val jobIds = parsed.map { it.jobId }.distinct()
            val jobsFuture = CompletableFuture.supplyAsync(
                { jobPort.findJobsByIds(jobIds).associateBy { it.jobId } },
                asyncExecutor,
            )
            val resultsFuture = CompletableFuture.supplyAsync(
                { resultPort.findByJobIds(jobIds).associateBy { it.jobId } },
                asyncExecutor,
            )
            val jobsById = jobsFuture.join()
            val resultsByJobId = resultsFuture.join()
            timer.mark("loadCalculationResults")
            val outcomes = buildPgmqProjectionCommands(parsed, jobsById, resultsByJobId)
            timer.mark("buildViewRows")
            val commands = outcomes.mapNotNull { it.command }
            archiveIds += outcomes.filter { it.archive }.map { it.messageId }

            if (commands.isNotEmpty()) {
                viewQueryPort.batchUpsertFromCalculations(commands)
            }
            timer.mark("batchUpsertViews")
            archiveIfNeeded(archiveIds)
            timer.mark("archiveMessages")
        } finally {
            timer.close(log)
        }
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
        val totalExpectedCost: Long
        val maxPresetNo: Int
        val presetsJson: String

        val tec = resultData.totalExpectedCost
        val mpn = resultData.maxPresetNo
        val pj = resultData.presetsJson

        if (tec != null && mpn != null && pj != null) {
            totalExpectedCost = tec
            maxPresetNo = mpn
            presetsJson = pj
        } else {
            val resultJson = decompress(resultData.responseBody)
            val tree = objectMapper.readTree(resultJson)
            totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return null
            maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return null
            val presetsNode = tree.get("presets")
            presetsJson = if (presetsNode != null) objectMapper.writeValueAsString(presetsNode) else "[]"
        }

        val presetNo = (message.payload["presetNo"] as? Number)?.toInt() ?: 1
        val characterId = message.payload["characterId"]?.toString()

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
}
