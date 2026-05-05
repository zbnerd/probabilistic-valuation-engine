package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import maple.calculator.config.PipelineProperties
import maple.calculator.event.SnapshotChunkReadyEvent
import maple.calculator.parser.SnapshotEquipmentParser
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.storage.ObjectStorage
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.application.service.starforce.NoljangProbabilityTable
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentItemConverter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class CalculationResult(
    val ocid: String,
    val presetNo: Int,
    val itemName: String,
    val itemLevel: Int,
    val itemPart: String?,
    val itemEquipmentPart: String?,
    val potentialGrade: String?,
    val potentialOptions: List<String?>,
    val additionalGrade: String?,
    val additionalOptions: List<String>,
    val currentStar: Int,
    val targetStar: Int,
    val status: String,
    val totalCost: Double?,
    val blackCubeCost: Double?,
    val additionalCubeCost: Double?,
    val starforceCost: Double?,
    val errorMessage: String? = null,
)

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val equipmentParser: SnapshotEquipmentParser,
    private val calculationCache: CalculationCache,
    private val objectMapper: ObjectMapper,
    private val properties: PipelineProperties,
    private val resultWriter: CalculationResultWriter,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)
    private val sampleCount = AtomicInteger(0)

    data class FlatItem(
        val ocid: String,
        val presetNo: Int,
        val item: EquipmentItem,
    )

    data class ChunkResult(
        val recordCount: Int,
        val successCount: Int,
        val totalItems: Int,
        val calculatedCount: Int,
        val errorCount: Int,
        val resultObjectKey: String,
        val resultCount: Int,
        val resultUncompressedBytes: Long,
        val resultCompressedBytes: Long,
    )

    suspend fun process(event: SnapshotChunkReadyEvent): ChunkResult = coroutineScope {
        val lineChannel = Channel<String>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val resultChannel = Channel<CalculationResult>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val resultObjectKey = resultObjectKeyFor(event)

        launch(Dispatchers.IO) { readLines(event.objectKey, lineChannel) }

        launch {
            coroutineScope {
                repeat(properties.workerCount) {
                    launch(Dispatchers.Default) {
                        parseLines(lineChannel, itemChannel, recordCount, successCount, totalItems)
                    }
                }
            }
            itemChannel.close()
        }

        launch {
            coroutineScope {
                repeat(properties.workerCount) {
                    launch(Dispatchers.Default) {
                        processItems(itemChannel, resultChannel, calculatedCount, errorCount)
                    }
                }
            }
            resultChannel.close()
        }

        val writeResult = async(Dispatchers.IO) {
            resultWriter.write(resultObjectKey, resultChannel)
        }.await()

        ChunkResult(
            recordCount = recordCount.get(),
            successCount = successCount.get(),
            totalItems = totalItems.get(),
            calculatedCount = calculatedCount.get(),
            errorCount = errorCount.get(),
            resultObjectKey = writeResult.objectKey,
            resultCount = writeResult.resultCount,
            resultUncompressedBytes = writeResult.uncompressedBytes,
            resultCompressedBytes = writeResult.compressedBytes,
        )
    }

    private fun resultObjectKeyFor(event: SnapshotChunkReadyEvent): String = "data/calculator/runs/${event.runId}/${event.endpoint}/chunks/result-${event.chunkId}.jsonl.gz"

    private suspend fun readLines(
        objectKey: String,
        channel: Channel<String>,
    ) {
        objectStorage.openInputStream(objectKey).use { stream ->
            jsonlReader.readLines(stream).collect { line ->
                channel.send(line)
            }
        }
        channel.close()
    }

    private suspend fun parseLines(
        lineChannel: Channel<String>,
        itemChannel: Channel<FlatItem>,
        recordCount: AtomicInteger,
        successCount: AtomicInteger,
        totalItems: AtomicInteger,
    ) {
        for (line in lineChannel) {
            recordCount.incrementAndGet()
            val node = objectMapper.readTree(line)
            if (node.path("status").asText() != "SUCCESS") continue
            val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: continue
            val ocid = node.path("key").asText("")
            successCount.incrementAndGet()

            for ((presetNo, items) in equipmentParser.parseAllPresets(body)) {
                for (item in items) {
                    totalItems.incrementAndGet()
                    itemChannel.send(FlatItem(ocid, presetNo, item))
                }
            }
        }
    }

    private suspend fun processItems(
        itemChannel: Channel<FlatItem>,
        resultChannel: Channel<CalculationResult>,
        calculatedCount: AtomicInteger,
        errorCount: AtomicInteger,
    ) {
        for (flatItem in itemChannel) {
            val result = calculateItem(flatItem)
            if (result.status == "ERROR") {
                errorCount.incrementAndGet()
            } else {
                calculatedCount.incrementAndGet()
            }
            resultChannel.send(result)
        }
    }

    private fun calculateItem(flatItem: FlatItem): CalculationResult = runCatching {
        val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
        val componentCosts = calculateComponentCosts(cubeInput)
        val status = if (componentCosts.hasAnyCost) "SUCCESS" else "SKIPPED"
        val result = buildCalculationResult(flatItem, cubeInput, componentCosts, status, null)
        logSample(result)
        result
    }.getOrElse { ex ->
        val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
        log.warn("Calculation error: ocid={} preset={}: {}", flatItem.ocid, flatItem.presetNo, ex.message)
        buildCalculationResult(flatItem, cubeInput, ComponentCosts.empty(), "ERROR", ex.message)
    }

    private fun calculateComponentCosts(cubeInput: CubeCalculationInput): ComponentCosts {
        val potentialCost = if (cubeInput.isReady()) {
            calculationCache.calculatePotential(cubeInput)
        } else {
            null
        }

        val additionalCost = if (hasAdditionalPotential(cubeInput)) {
            calculationCache.calculateAdditional(cubeInput.toAdditionalCubeInput())
        } else {
            null
        }

        val starforceTarget = targetStar(cubeInput)
        val starforceCost = if (starforceTarget > 0) {
            calculationCache.calculateStarforce(cubeInput.itemName ?: "", cubeInput.level, 0, starforceTarget)
        } else {
            null
        }

        return ComponentCosts(
            blackCubeCost = potentialCost,
            additionalCubeCost = additionalCost,
            starforceCost = starforceCost,
        )
    }

    private fun hasAdditionalPotential(cubeInput: CubeCalculationInput): Boolean = cubeInput.additionalGrade != null &&
        cubeInput.additionalOptions.any { it.trim().isNotEmpty() && !"null".equals(it, ignoreCase = true) }

    private fun CubeCalculationInput.toAdditionalCubeInput(): CubeCalculationInput = copy(
        grade = additionalGrade,
        options = additionalOptions.toMutableList(),
    )

    private fun targetStar(cubeInput: CubeCalculationInput): Int {
        if (cubeInput.starforce <= 0 || cubeInput.itemName.isNullOrBlank() || cubeInput.level <= 0) {
            return 0
        }
        return if (cubeInput.isNoljangEquipment()) {
            minOf(cubeInput.starforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
        } else {
            cubeInput.starforce
        }
    }

    private fun buildCalculationResult(
        flatItem: FlatItem,
        cubeInput: CubeCalculationInput,
        componentCosts: ComponentCosts,
        status: String,
        errorMessage: String?,
    ): CalculationResult = CalculationResult(
        ocid = flatItem.ocid,
        presetNo = flatItem.presetNo,
        itemName = cubeInput.itemName ?: "",
        itemLevel = cubeInput.level,
        itemPart = cubeInput.part,
        itemEquipmentPart = cubeInput.itemEquipmentPart,
        potentialGrade = cubeInput.grade,
        potentialOptions = cubeInput.options,
        additionalGrade = cubeInput.additionalGrade,
        additionalOptions = cubeInput.additionalOptions,
        currentStar = 0,
        targetStar = targetStar(cubeInput),
        status = status,
        totalCost = componentCosts.totalCost,
        blackCubeCost = componentCosts.blackCubeCost,
        additionalCubeCost = componentCosts.additionalCubeCost,
        starforceCost = componentCosts.starforceCost,
        errorMessage = errorMessage,
    )

    private fun logSample(result: CalculationResult) {
        if (sampleCount.incrementAndGet() <= 10) {
            log.debug("[SAMPLE] {}", objectMapper.writeValueAsString(result))
        }
    }

    private data class ComponentCosts(
        val blackCubeCost: Double?,
        val additionalCubeCost: Double?,
        val starforceCost: Double?,
    ) {
        val hasAnyCost: Boolean = blackCubeCost != null || additionalCubeCost != null || starforceCost != null
        val totalCost: Double?
            get() = if (hasAnyCost) {
                (blackCubeCost ?: 0.0) + (additionalCubeCost ?: 0.0) + (starforceCost ?: 0.0)
            } else {
                null
            }

        companion object {
            fun empty(): ComponentCosts = ComponentCosts(null, null, null)
        }
    }
}
