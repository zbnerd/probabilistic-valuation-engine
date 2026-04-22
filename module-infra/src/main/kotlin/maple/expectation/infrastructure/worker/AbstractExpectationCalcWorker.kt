package maple.expectation.infrastructure.worker

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import maple.expectation.core.port.inbound.BatchComputeBuffer
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.inbound.CharacterViewQueryPort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.core.port.out.EquipmentFanOutPort
import maple.expectation.core.port.out.GameCharacterPort
import maple.expectation.infrastructure.cache.tiered.L2CacheStrategy
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.CalculationResult
import maple.expectation.infrastructure.pgmq.ExpectationCalcMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics
import maple.expectation.infrastructure.persistence.repository.CharacterViewBatchRepository
import maple.expectation.infrastructure.persistence.repository.CharacterViewBatchRepository.ParsedViewResult
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
    private val characterOcidPort: CharacterOcidPort,
    private val equipmentFanOutPort: EquipmentFanOutPort,
    private val preWarmExecutor: Executor,
    // Two-phase batch processing dependencies
    private val gameCharacterPort: GameCharacterPort,
    private val l2CacheStrategy: L2CacheStrategy,
    private val cacheProperties: CacheProperties,
    private val transactionTemplate: TransactionTemplate,
    private val viewQueryPort: CharacterViewQueryPort,
    private val batchRepo: CharacterViewBatchRepository,
    private val objectMapper: ObjectMapper,
    private val computeBuffer: BatchComputeBuffer,
) : PgmqWorker<ExpectationCalcMessage>(pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper) {

    override val payloadClass: Class<ExpectationCalcMessage> = ExpectationCalcMessage::class.java

    protected abstract val workerName: String
    protected abstract val workerLog: Logger

    override val supportsTwoPhase: Boolean = true

    override fun preWarmBatch(messages: List<PgmqMessage<ExpectationCalcMessage>>) {
        computeBuffer.clear()  // Clear compute dedup buffer for new batch
        val context = TaskContext.of(workerName, "PreWarm", queueName)

        executor.executeVoid({
            val igns = messages.asSequence().map { it.payload.userIgn }.toSet()
            if (igns.isEmpty()) return@executeVoid

            val ignToOcid = characterOcidPort.resolveOcids(igns)
            if (ignToOcid.isEmpty()) return@executeVoid

            val warmupFutures = ignToOcid.values.map { ocid ->
                CompletableFuture.supplyAsync(
                    { equipmentFanOutPort.preFetchByOcid(ocid) },
                    preWarmExecutor,
                )
            }

            CompletableFuture.allOf(*warmupFutures.toTypedArray())
                .orTimeout(15, TimeUnit.SECONDS)
                .handle { _, _ -> null }
                .join()

            workerLog.info("[{}] Pre-warm: {} igns -> {} ocids", workerName, igns.size, ignToOcid.size)
        }, context)
    }

    override fun process(message: PgmqMessage<ExpectationCalcMessage>): Boolean {
        val request = message.payload
        val context = TaskContext.of(workerName, "Process", request.userIgn)

        return executor.executeOrDefault({
            workerLog.info("[{}] Processing: userIgn={}, taskId={}", workerName, request.userIgn, message.messageId)

            expectationPort.calculateExpectationAsync(
                request.userIgn,
                request.forceRecalculation,
                message.messageId.toString(),
            ).join()

            workerLog.info("[{}] Completed: userIgn={}, taskId={}", workerName, request.userIgn, message.messageId)
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
            workerLog.warn("[{}] Duplicate userIgn in batch: {}", workerName,
                grouped.filter { it.value.size > 1 }.keys)
        }
        val deduped = grouped.mapValues { it.value.last() }.values.toList()

        // 2. Parse all results in one pass
        val parsed = deduped.mapNotNull { result ->
            try {
                val tree = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(result.response)
                val totalExpectedCost = tree.get("totalExpectedCost")?.asLong() ?: return@mapNotNull null
                val maxPresetNo = tree.get("maxPresetNo")?.asInt() ?: return@mapNotNull null
                val presetsNode = tree.get("presets") ?: return@mapNotNull null
                val char = result.character
                ParsedViewResult(
                    userIgn = char.userIgn.value,
                    messageId = result.message.messageId.toString(),
                    characterOcid = char.characterId.value,
                    characterClass = char.characterClass ?: "",
                    totalExpectedCost = totalExpectedCost,
                    maxPresetNo = maxPresetNo,
                    presetsJson = objectMapper.writeValueAsString(presetsNode),
                    version = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                workerLog.warn("[{}] Parse failed for {}: {}", workerName, result.character.userIgn.value, e.message)
                null
            }
        }
        if (parsed.isEmpty()) return

        // 3. Bulk upsert — 3 queries total (SELECT + batch UPDATE/INSERT)
        batchRepo.bulkUpsert(parsed)
    }

    private fun batchL2CachePut(results: List<CalculationResult>) {
        val entries = results.map { result ->
            val cacheKey = "expectationV4:${cacheProperties.keyVersion}:${result.message.payload.userIgn}"
            cacheKey to result.response
        }
        val spec = cacheProperties.specs["expectationV4"]
            ?: throw IllegalStateException("Cache spec 'expectationV4' not configured in cache.specs")
        l2CacheStrategy.putAll(entries, spec.l2TtlMinutes.toLong())
    }
}
