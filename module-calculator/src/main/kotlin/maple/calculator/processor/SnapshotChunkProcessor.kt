package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import maple.calculator.config.CoroutineDispatcherConverter
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.model.ChunkResult
import maple.calculator.parser.SnapshotEquipmentParser
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.expectation.common.storage.ObjectStorage
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentItemConverter
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
    private val parseWorkerCount: Int = requireNotNull(properties.parseWorkers.takeIf { it > 0 }) {
        "calculator.pipeline.parse-workers must be positive: ${properties.parseWorkers}"
    }
    private val calcWorkerCount: Int = requireNotNull(properties.calcWorkers.takeIf { it > 0 }) {
        "calculator.pipeline.calc-workers must be positive: ${properties.calcWorkers}"
    }
    private val dispatcherConverter = CoroutineDispatcherConverter()
    private val parseDispatcher: CoroutineDispatcher = dispatcherConverter.convert(properties.parseDispatcher)
    private val calcDispatcher: CoroutineDispatcher = dispatcherConverter.convert(properties.calcDispatcher)

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
                repeat(parseWorkerCount) {
                    launch(this@SnapshotChunkProcessor.parseDispatcher) {
                        parseLines(lineChannel, itemChannel, recordCount, successCount, totalItems)
                    }
                }
            }
            itemChannel.close()
        }

        launch {
            coroutineScope {
                repeat(calcWorkerCount) {
                    launch(this@SnapshotChunkProcessor.calcDispatcher) {
                        processItems(itemChannel, resultChannel, calculatedCount, errorCount)
                    }
                }
            }
            resultChannel.close()
        }

        val writeResult = async(Dispatchers.IO) {
            resultWriter.write(resultObjectKey, channelAsFlow(resultChannel))
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
        objectStorage.getStream(objectKey).use { stream ->
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
            // Recent chunk writes carry the response body as a base64-encoded
            // ByteArray field `bodyBytes` (Jackson serializes ByteArray as
            // base64). Older writes inlined the body as a nested `body` JSON
            // object. Accept either shape; absent both, treat the record as
            // missing payload and skip.
            val status = node.path("status").asText("")
            val httpStatus = node.path("httpStatus").asInt(0)
            val isSuccess = status == "SUCCESS" || (status.isBlank() && httpStatus == 200)
            if (!isSuccess) continue

            val body = extractBody(node) ?: continue
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

    /**
     * Return the response body node, accepting both:
     *  - inline `body` JSON object (older writes)
     *  - `bodyBytes` (base64-encoded JSON bytes) — Jackson default for ByteArray
     *
     * Returns null if neither is present, or if the bodyBytes base64 decode /
     * JSON parse fails.
     */
    private fun extractBody(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode? {
        val inline = node.path("body")
        if (!inline.isMissingNode && !inline.isNull) return inline
        val bodyBytesField = node.path("bodyBytes")
        if (bodyBytesField.isMissingNode || bodyBytesField.isNull) return null
        val b64 = bodyBytesField.asText("")
        if (b64.isBlank()) return null
        return runCatching {
            val raw = java.util.Base64.getDecoder().decode(b64)
            objectMapper.readTree(raw)
        }.getOrNull()
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
        if (sampleCount.incrementAndGet() <= 10) {
            log.debug("[SAMPLE] {}", objectMapper.writeValueAsString(result))
        }
    }

    /** Convert a Channel<T> to Flow<T> for consumers that need a Flow (e.g. write()). */
    private fun <T> channelAsFlow(channel: Channel<T>): Flow<T> = flow {
        for (e in channel) {
            emit(e)
        }
    }
}
