package maple.calculator.processor

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.model.ChunkResult
import maple.calculator.parser.SnapshotEquipmentParser
import maple.calculator.parser.SnapshotLineParser
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.storage.ObjectStorage
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentItemConverter
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Sample the first 10 records per chunk for debug logging. */
private const val SAMPLE_LOG_LIMIT: Int = 10

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val equipmentParser: SnapshotEquipmentParser,
    private val calculationCache: CalculationCache,
    private val lineParser: SnapshotLineParser,
    private val sampleLogSerializer: SampleLogSerializer,
    private val properties: PipelineProperties,
    private val resultWriter: CalculationResultWriter,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)
    private val sampleCount = AtomicInteger(0)
    private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
        "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
    }

    data class FlatItem(
        val ocid: String,
        val presetNo: Int,
        val item: EquipmentItem,
    )

    suspend fun process(event: SnapshotChunkReadyEvent, resultObjectKey: String): ChunkResult = coroutineScope {
        val lineChannel = Channel<String>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val resultChannel = Channel<CalculationResult>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        launch(Dispatchers.IO) { readLines(event.objectKey, lineChannel) }

        launch {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        parseLines(lineChannel, itemChannel, recordCount, successCount, totalItems)
                    }
                }
            }
            itemChannel.close()
        }

        launch {
            coroutineScope {
                repeat(workerCount) {
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
            val record = lineParser.parse(line) ?: continue
            successCount.incrementAndGet()
            val ocid = record.ocid
            val body = record.body

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
        val componentCosts = calculateComponentCosts(cubeInput, flatItem.presetNo)
        val status = if (componentCosts.hasAnyCost) "SUCCESS" else "SKIPPED"
        val result = EquipmentCalculationInputConverter.toCalculationResult(flatItem.ocid, flatItem.presetNo, cubeInput, componentCosts, status, null)
        logSample(result)
        result
    }.getOrElse { ex ->
        val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
        log.warn("Calculation error: ocid={} preset={}: {}", StringMaskingUtils.maskOcid(flatItem.ocid), flatItem.presetNo, ex.message)
        EquipmentCalculationInputConverter.toCalculationResult(flatItem.ocid, flatItem.presetNo, cubeInput, CalculationCache.ComponentCosts.empty(), "ERROR", ex.message)
    }

    private fun calculateComponentCosts(cubeInput: CubeCalculationInput, presetNo: Int): CalculationCache.ComponentCosts {
        val input = EquipmentCalculationInputConverter.toCalculationInput(cubeInput, presetNo)
        return calculationCache.calculate(input)
    }

    private fun logSample(result: CalculationResult) {
        if (sampleCount.incrementAndGet() <= SAMPLE_LOG_LIMIT) {
            log.debug("[SAMPLE] {}", sampleLogSerializer.serialize(result))
        }
    }
}
