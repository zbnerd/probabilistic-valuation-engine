package maple.calculator.reader

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.onEach
import maple.calculator.metrics.ChunkParserMetrics
import maple.common.parser.StreamingChunkParser
import org.springframework.stereotype.Component

/**
 * Reads a gz-compressed JSONL snapshot chunk as a [kotlinx.coroutines.flow.Flow]
 * of record Maps. Uses [StreamingChunkParser] for streaming parse.
 *
 * Memory: O(1) per record; no per-line `readTree` round-trip.
 *
 * Instruments `chunk_parser_*` metrics with `source="snapshot_record"`.
 */
@Component
class GzipJsonlSnapshotRecordReader(
    private val streamingChunkParser: StreamingChunkParser,
    private val chunkParserMetrics: ChunkParserMetrics,
) {
    /**
     * Hot-path Flow consumer. Per-record emitted counter; parse duration
     * recorded on Flow completion (collector's terminal signal).
     */
    fun readRecords(inputStream: InputStream) = streamingChunkParser.parse(inputStream)
        .onEach { chunkParserMetrics.recordsEmitted("snapshot_record").increment() }

    /**
     * Cold-path helper for callers that need a materialized list.
     */
    suspend fun readRecordsAsList(inputStream: InputStream): List<Map<String, Any>> {
        val emitted = chunkParserMetrics.recordsEmitted("snapshot_record")
        val timer = chunkParserMetrics.parseDuration("snapshot_record")
        val start = System.nanoTime()
        val result = mutableListOf<Map<String, Any>>()
        streamingChunkParser.parse(inputStream).collect { record ->
            emitted.increment()
            result.add(record)
        }
        timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        return result
    }
}
