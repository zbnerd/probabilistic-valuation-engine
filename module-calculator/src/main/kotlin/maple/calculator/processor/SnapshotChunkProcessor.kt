package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import maple.calculator.config.CoroutineDispatcherConverter
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.model.ChunkResult
import maple.calculator.parser.SnapshotEquipmentParser
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.storage.ObjectStorage
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
        val recordChannel = Channel<Map<String, Any>>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val resultChannel = Channel<CalculationResult>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        launch(Dispatchers.IO) { readLines(event.objectKey, recordChannel) }

        launch {
            coroutineScope {
                repeat(parseWorkerCount) {
                    launch(this@SnapshotChunkProcessor.parseDispatcher) {
                        parseLines(recordChannel, itemChannel, recordCount, successCount, totalItems)
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

        // Start the write CF BEFORE waiting for parse+calc to finish —
        // the CF drains resultChannel in the background via
        // producerScope.future, so it overlaps with the parse+calc workers
        // (same overlap the original async { write() } provided).
        val writeFuture = resultWriter.write(resultObjectKey, channelAsFlow(resultChannel))
        val writeResult = writeFuture.await()  // single .await() at coroutine→CF boundary

        ChunkResult(
            recordCount = recordCount.get(),
            successCount = successCount.get(),
            totalItems = totalItems.get(),
            calculatedCount = calculatedCount.get(),
            errorCount = errorCount.get(),
            resultObjectKey = writeResult.objectKey,
            resultCount = writeResult.resultCount.toInt(),
            resultUncompressedBytes = writeResult.uncompressedBytes,
            resultCompressedBytes = writeResult.compressedBytes,
        )
    }

    private suspend fun readLines(
        objectKey: String,
        channel: Channel<Map<String, Any>>,
    ) {
        objectStorage.getStream(objectKey).use { stream ->
            jsonlReader.readRecords(stream).collect { record ->
                channel.send(record)
            }
        }
        channel.close()
    }

    private suspend fun parseLines(
        recordChannel: Channel<Map<String, Any>>,
        itemChannel: Channel<FlatItem>,
        recordCount: AtomicInteger,
        successCount: AtomicInteger,
        totalItems: AtomicInteger,
    ) {
        for (record in recordChannel) {
            recordCount.incrementAndGet()
            // Recent chunk writes carry the response body as a base64-encoded
            // ByteArray field `bodyBytes` (Jackson serializes ByteArray as
            // base64). Older writes inlined the body as a nested `body` JSON
            // object. Accept either shape; absent both, treat the record as
            // missing payload and skip.
            val status = record["status"] as? String ?: ""
            val httpStatus = (record["httpStatus"] as? Number)?.toInt() ?: 0
            val isSuccess = status == "SUCCESS" || (status.isBlank() && httpStatus == 200)
            if (!isSuccess) continue

            val body = extractBody(record) ?: continue
            val ocid = record["key"] as? String ?: ""
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
    private fun extractBody(record: Map<String, Any>): com.fasterxml.jackson.databind.JsonNode? {
        val inline = record["body"]
        if (inline != null && inline !is Map<*, *>) {
            // body should be a Map; if not, fall through
        } else if (inline is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return objectMapper.valueToTree(inline as Map<String, Any?>)
        }
        val b64 = record["bodyBytes"] as? String
        if (b64.isNullOrBlank()) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(b64)
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
