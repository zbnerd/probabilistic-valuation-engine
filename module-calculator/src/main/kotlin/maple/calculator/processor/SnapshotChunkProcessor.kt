package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import maple.calculator.model.CalculationResult
import maple.calculator.model.ChunkResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import maple.calculator.pipeline.SnapshotChunkPipeline
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.storage.ObjectStorage
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentItemConverter
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val parser: SnapshotChunkParser,
    private val pipeline: SnapshotChunkPipeline,
    private val calculationCache: CalculationCache,
    private val objectMapper: ObjectMapper,
    private val sampleLogSerializer: SampleLogSerializer,
    private val resultWriter: CalculationResultWriter,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)
    private val sampleCount = AtomicInteger(0)

    suspend fun process(event: SnapshotChunkReadyEvent, resultObjectKey: String): ChunkResult {
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val source: Flow<String> = flow {
            objectStorage.openInputStream(event.objectKey).use { stream ->
                emitAll(jsonlReader.readLines(stream))
            }
        }

        val resultFlow = pipeline.run(
            source = source,
            parse = { line ->
                recordCount.incrementAndGet()
                when (val outcome = parser.parse(line)) {
                    SnapshotChunkParser.Outcome.Skipped -> outcome
                    is SnapshotChunkParser.Outcome.Parsed -> {
                        successCount.incrementAndGet()
                        totalItems.addAndGet(outcome.items.size)
                        outcome
                    }
                }
            },
            calculate = { flatItem -> calculateItem(flatItem, calculatedCount, errorCount) },
        )

        val writeResult = resultWriter.write(resultObjectKey, resultFlow)

        return ChunkResult(
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

    private fun calculateItem(
        flatItem: FlatItem,
        calculatedCount: AtomicInteger,
        errorCount: AtomicInteger,
    ): CalculationResult {
        val result = runCatching {
            val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
            val componentCosts = calculateComponentCosts(cubeInput, flatItem.presetNo)
            val status = if (componentCosts.hasAnyCost) "SUCCESS" else "SKIPPED"
            val successResult = EquipmentCalculationInputConverter.toCalculationResult(
                flatItem.ocid, flatItem.presetNo, cubeInput, componentCosts, status, null,
            )
            logSample(successResult)
            successResult
        }.getOrElse { ex ->
            val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
            log.warn(
                "Calculation error: ocid={} preset={}: {}",
                StringMaskingUtils.maskOcid(flatItem.ocid),
                flatItem.presetNo,
                ex.message,
            )
            EquipmentCalculationInputConverter.toCalculationResult(
                flatItem.ocid, flatItem.presetNo, cubeInput, CalculationCache.ComponentCosts.empty(), "ERROR", ex.message,
            )
        }

        if (result.status == "ERROR") errorCount.incrementAndGet() else calculatedCount.incrementAndGet()
        return result
    }

    private fun calculateComponentCosts(cubeInput: CubeCalculationInput, presetNo: Int): CalculationCache.ComponentCosts {
        val input = EquipmentCalculationInputConverter.toCalculationInput(cubeInput, presetNo)
        return calculationCache.calculate(input)
    }

    private fun logSample(result: CalculationResult) {
        if (sampleCount.incrementAndGet() <= 10) {
            log.debug("[SAMPLE] {}", sampleLogSerializer.serialize(result))
        }
    }
}
