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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val equipmentParser: SnapshotEquipmentParser,
    private val objectMapper: ObjectMapper,
    private val properties: PipelineProperties,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)

    data class ChunkResult(
        val recordCount: Int,
        val successCount: Int,
        val totalItems: Int,
    )

    fun process(objectKey: String): ChunkResult = runBlocking {
        val channel = Channel<String>(properties.channelCapacity)
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)

        coroutineScope {
            // Reader: gzip decompress + line read only (pure IO)
            launch(Dispatchers.IO) {
                objectStorage.openInputStream(objectKey).use { stream ->
                    jsonlReader.readLines(stream).collect { line ->
                        channel.send(line)
                    }
                }
                channel.close()
            }

            // N Workers: all JSON parse + preset extraction (pure CPU)
            coroutineScope {
                repeat(properties.workerCount) {
                    launch(Dispatchers.Default) {
                        for (line in channel) {
                            recordCount.incrementAndGet()
                            val node = objectMapper.readTree(line)
                            if (node.path("status").asText() != "SUCCESS") continue
                            val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: continue
                            successCount.incrementAndGet()
                            val presets = equipmentParser.parseAllPresets(body)
                            totalItems.addAndGet(presets.values.sumOf { it.size })
                        }
                    }
                }
            }
        }

        ChunkResult(recordCount.get(), successCount.get(), totalItems.get())
    }
}
