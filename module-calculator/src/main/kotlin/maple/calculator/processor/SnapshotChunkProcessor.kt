package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import maple.calculator.config.PipelineProperties
import maple.calculator.parser.SnapshotEquipmentParser
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.storage.ObjectStorage
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.application.service.starforce.NoljangProbabilityTable
import maple.expectation.core.domain.equipment.SecondaryWeaponCategory
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentItemConverter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val equipmentParser: SnapshotEquipmentParser,
    private val calculationCache: CalculationCache,
    private val calculatorFactory: EquipmentExpectationCalculatorFactory,
    private val objectMapper: ObjectMapper,
    private val properties: PipelineProperties,
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
    )

    fun process(objectKey: String): ChunkResult = runBlocking {
        val lineChannel = Channel<String>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        coroutineScope {
            // 1. reader: gzip에서 raw line만 순차 읽기
            launch(Dispatchers.IO) { readLines(objectKey, lineChannel) }

            // 2. N parsers → itemChannel close when all done
            launch {
                coroutineScope {
                    repeat(properties.workerCount) {
                        launch(Dispatchers.Default) { parseLines(lineChannel, itemChannel, recordCount, successCount, totalItems) }
                    }
                }
                itemChannel.close()
            }

            // 3. N workers: 계산 (병렬)
            repeat(properties.workerCount) {
                launch(Dispatchers.Default) { processItems(itemChannel, calculatedCount, errorCount) }
            }
        }

        ChunkResult(recordCount.get(), successCount.get(), totalItems.get(), calculatedCount.get(), errorCount.get())
    }

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
        channel: Channel<FlatItem>,
        calculatedCount: AtomicInteger,
        errorCount: AtomicInteger,
    ) {
        for (flatItem in channel) {
            calculateItem(flatItem, calculatedCount, errorCount)
        }
    }

    private fun calculateItem(
        flatItem: FlatItem,
        calculatedCount: AtomicInteger,
        errorCount: AtomicInteger,
    ) {
        val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
        if (!cubeInput.isReady()) {
            calculatedCount.incrementAndGet()
            return
        }
        try {
            val shouldSample = sampleCount.incrementAndGet() <= 10

            if (shouldSample) {
                val input = buildCalculationInput(cubeInput, flatItem.presetNo)
                val calculator = calculatorFactory.createFullCalculator(input)
                val cost = calculator.calculateCost()
                val details = calculator.detailedCosts
                val sample = mapOf(
                    "ocid" to flatItem.ocid,
                    "presetNo" to flatItem.presetNo,
                    "itemName" to cubeInput.itemName,
                    "itemLevel" to cubeInput.level,
                    "potentialGrade" to cubeInput.grade,
                    "potentialOption1" to cubeInput.options.getOrNull(0),
                    "potentialOption2" to cubeInput.options.getOrNull(1),
                    "potentialOption3" to cubeInput.options.getOrNull(2),
                    "additionalGrade" to cubeInput.additionalGrade,
                    "additionalOption1" to cubeInput.additionalOptions.getOrNull(0),
                    "additionalOption2" to cubeInput.additionalOptions.getOrNull(1),
                    "additionalOption3" to cubeInput.additionalOptions.getOrNull(2),
                    "starforce" to cubeInput.starforce,
                    "totalCost" to cost,
                    "blackCubeCost" to details.blackCubeCost,
                    "additionalCubeCost" to details.additionalCubeCost,
                    "starforceCost" to details.starforceCost,
                    "enhancePath" to calculator.enhancePath,
                )
                log.debug("[SAMPLE] {}", objectMapper.writeValueAsString(sample))
            } else {
                if (cubeInput.grade != null && !cubeInput.options.isNullOrEmpty()) {
                    calculationCache.calculatePotential(cubeInput)
                }
                if (cubeInput.additionalGrade != null && !cubeInput.additionalOptions.isNullOrEmpty()) {
                    calculationCache.calculateAdditional(cubeInput)
                }
                if (cubeInput.starforce > 0) {
                    val isNoljang = cubeInput.isNoljangEquipment()
                    val targetStar = if (isNoljang)
                        minOf(cubeInput.starforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
                    else
                        cubeInput.starforce
                    calculationCache.calculateStarforce(cubeInput.itemName ?: "", cubeInput.level, 0, targetStar)
                }
            }

            calculatedCount.incrementAndGet()
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            log.warn("Calculation error: ocid={}, preset={}: {}", flatItem.ocid, flatItem.presetNo, e.message)
        }
    }

    private fun buildCalculationInput(
        cubeInput: CubeCalculationInput,
        presetNo: Int,
    ): EquipmentCalculationInput {
        val isNoljang = cubeInput.isNoljangEquipment()
        val targetStar = if (isNoljang)
            minOf(cubeInput.starforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
        else
            cubeInput.starforce
        val potentialPart = SecondaryWeaponCategory.resolvePotentialPart(
            cubeInput.part, cubeInput.itemEquipmentPart,
        )
        return EquipmentCalculationInput.builder()
            .itemName(cubeInput.itemName ?: "")
            .itemPart(potentialPart)
            .itemEquipmentPart(cubeInput.itemEquipmentPart ?: "")
            .itemIcon(cubeInput.itemIcon ?: "")
            .itemLevel(cubeInput.level)
            .presetNo(presetNo)
            .isNoljang(isNoljang)
            .potentialGrade(cubeInput.grade)
            .potentialOptions(cubeInput.options?.filterNotNull())
            .additionalPotentialGrade(cubeInput.additionalGrade)
            .additionalPotentialOptions(cubeInput.additionalOptions?.filterNotNull())
            .currentStar(0)
            .targetStar(targetStar)
            .build()
    }
}
