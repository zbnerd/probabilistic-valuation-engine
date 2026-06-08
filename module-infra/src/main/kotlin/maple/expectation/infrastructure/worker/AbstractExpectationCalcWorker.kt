package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.GameCharacterPort
import maple.expectation.infrastructure.cache.tiered.L2CacheStrategy
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.persistence.repository.CharacterViewBatchRepository
import maple.expectation.infrastructure.persistence.repository.CharacterViewBatchRepository.ParsedViewResult
import maple.expectation.infrastructure.pgmq.CalculationResult
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import org.slf4j.Logger
import org.springframework.transaction.support.TransactionTemplate

abstract class AbstractExpectationCalcWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    meterRegistry: MeterRegistry,
    queueMetrics: WorkerQueueMetrics,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    private val expectationPort: ExpectationV4Port,
    // Two-phase batch processing dependencies
    private val gameCharacterPort: GameCharacterPort,
    private val l2CacheStrategy: L2CacheStrategy,
    private val cacheProperties: CacheProperties,
    private val transactionTemplate: TransactionTemplate,
    private val viewQueryPort: CharacterViewQueryPort,
    private val batchRepo: CharacterViewBatchRepository,
    private val objectMapper: ObjectMapper,
    private val jobService: CalculationJobService,
) : PgmqWorker<ExpectationCalcMessage>(pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java

    protected abstract val workerName: String
    protected abstract val workerLog: Logger

    override val supportsTwoPhase: Boolean = false

    override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean {
        val request = message.payload
        val context = TaskContext.of(workerName, "Process", request.userIgn)

        return executor.executeOrDefault({
            workerLog.info("[{}] Creating job: userIgn={}, taskId={}", workerName, request.userIgn, message.messageId)
            val claim = jobService.createOrFindActiveJob(null, request.userIgn, request.presetNo)
            if (claim.created) {
                jobService.dispatchToExternalApi(claim.job.jobId, request.userIgn, request.presetNo)
                workerLog.info("[{}] Job dispatched to external API pipeline: jobId={}", workerName, claim.job.jobId)
            } else {
                workerLog.info("[{}] Existing active job reused: jobId={}", workerName, claim.job.jobId)
            }
            true
        }, false, context)
    }

    override fun calculateOnly(message: PgmqMessage<ExpectationCalcMessage>): Any? {
        val request = message.payload
        val context = TaskContext.of(workerName, "CalculateOnly", request.userIgn)

        return executor.executeOrDefault({
            workerLog.info("[{}] Phase 1 calculateOnly: userIgn={}", workerName, request.userIgn)

            val response = expectationPort.calculateExpectationWriteOnly(
                request.userIgn,
                request.forceRecalculation,
                message.messageId.toString(),
                request.presetNo,
            )

            val character = gameCharacterPort.getCharacterOrThrow(request.userIgn)

            CalculationResult(
                message = message,
                response = response,
                character = character,
            )
        }, null, context)
    }

    override fun batchWrite(results: List<CalculationResult>) {
        if (results.isEmpty()) return

        val context = TaskContext.of(workerName, "BatchWrite", "${results.size}")
        executor.executeVoid({
            transactionTemplate.executeWithoutResult {
                if (results.size >= 10) {
                    workerLog.info("[{}] Phase 2 batchWrite: {} results", workerName, results.size)
                } else {
                    workerLog.debug("[{}] Phase 2 batchWrite: {} results", workerName, results.size)
                }

                batchL2CachePut(results)
                batchViewUpsert(results)

                val messageIds = results.map { it.message.messageId }
                val archived = pgmqClient.archiveBatch(queueName, messageIds)
                if (results.size >= 10) {
                    workerLog.info("[{}] Batch archived: {}/{}", workerName, archived, messageIds.size)
                }
            }
        }, context)
    }

    private fun batchViewUpsert(results: List<CalculationResult>) {
        // 1. Dedup by userIgn — keep latest result per character
        val grouped = results.groupBy { it.character.userIgn.value }
        if (grouped.any { it.value.size > 1 }) {
            workerLog.warn(
                "[{}] Duplicate userIgn in batch: {}",
                workerName,
                grouped.filter { it.value.size > 1 }.keys,
            )
        }
        val deduped = grouped.mapValues { it.value.last() }.values.toList()

        // 2. Parse all results in one pass
        val parsed = deduped.mapNotNull { result ->
            executor.executeOrDefault(
                {
                    val tree = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(result.response)
                    val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return@executeOrDefault null
                    val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return@executeOrDefault null
                    val presetsNode = tree.get("presets") ?: return@executeOrDefault null
                    val presetNo = result.message.payload.presetNo
                    val char = result.character
                    ParsedViewResult(
                        userIgn = char.userIgn.value,
                        messageId = result.message.messageId.toString(),
                        characterOcid = char.characterId.value,
                        characterClass = char.characterClass ?: "",
                        totalExpectedCost = totalExpectedCost,
                        maxPresetNo = maxPresetNo,
                        presetNo = presetNo,
                        presetsJson = objectMapper.writeValueAsString(presetsNode),
                        version = System.currentTimeMillis(),
                    )
                },
                null,
                TaskContext.of(workerName, "ParseResult", result.character.userIgn.value),
            )
        }
        if (parsed.isEmpty()) return

        // 3. Bulk upsert — 3 queries total (SELECT + batch UPDATE/INSERT)
        batchRepo.bulkUpsert(parsed)

        // 4. Sync read model for query-server — batch upsert (was N+1)
        val commands = parsed.map { view ->
            CharacterViewProjectionCommand(
                userIgn = view.userIgn,
                messageId = view.messageId,
                characterOcid = view.characterOcid,
                characterClass = view.characterClass,
                characterLevel = null,
                totalExpectedCost = view.totalExpectedCost,
                maxPresetNo = view.maxPresetNo,
                presetNo = view.presetNo,
                presetsJson = view.presetsJson,
            )
        }
        viewQueryPort.batchUpsertFromCalculations(commands)
    }

    private fun batchL2CachePut(results: List<CalculationResult>) {
        val entries = results.map { result ->
            val presetNo = result.message.payload.presetNo
            val cacheKey = if (presetNo == 1) {
                "expectationV4:${cacheProperties.keyVersion}:${result.message.payload.userIgn}"
            } else {
                "expectationV4:${cacheProperties.keyVersion}:${result.message.payload.userIgn}:p$presetNo"
            }
            cacheKey to result.response
        }
        val spec = cacheProperties.specs["expectationV4"]
            ?: throw IllegalStateException("Cache spec 'expectationV4' not configured in cache.specs")
        l2CacheStrategy.putAll(entries, spec.l2TtlMinutes.toLong())
    }
}
