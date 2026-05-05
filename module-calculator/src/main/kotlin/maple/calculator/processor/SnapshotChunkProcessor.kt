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
    private val calculatorFactory: EquipmentExpectationCalculatorFactory,
    private val objectMapper: ObjectMapper,
    private val properties: PipelineProperties,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)

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
        val channel = Channel<FlatItem>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        coroutineScope {
            launch(Dispatchers.IO) { readAndFlatten(objectKey, channel, recordCount, successCount, totalItems) }
            coroutineScope {
                repeat(properties.workerCount) {
                    launch(Dispatchers.Default) {
                        processItems(channel, calculatedCount, errorCount)
                    }
                }
            }
        }

        ChunkResult(recordCount.get(), successCount.get(), totalItems.get(), calculatedCount.get(), errorCount.get())
    }

    private suspend fun readAndFlatten(
        objectKey: String,
        channel: Channel<FlatItem>,
        recordCount: AtomicInteger,
        successCount: AtomicInteger,
        totalItems: AtomicInteger,
    ) {
        objectStorage.openInputStream(objectKey).use { stream ->
            jsonlReader.readLines(stream).collect { line ->
                recordCount.incrementAndGet()
                val node = objectMapper.readTree(line)
                if (node.path("status").asText() != "SUCCESS") return@collect
                val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: return@collect
                val ocid = node.path("key").asText("")
                successCount.incrementAndGet()

                for ((presetNo, items) in equipmentParser.parseAllPresets(body)) {
                    for (item in items) {
                        totalItems.incrementAndGet()
                        channel.send(FlatItem(ocid, presetNo, item))
                    }
                }
            }
        }
        channel.close()
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
            val input = buildCalculationInput(cubeInput, flatItem.presetNo)
            val calculator = calculatorFactory.createFullCalculator(input)
            calculator.calculateCost()
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
