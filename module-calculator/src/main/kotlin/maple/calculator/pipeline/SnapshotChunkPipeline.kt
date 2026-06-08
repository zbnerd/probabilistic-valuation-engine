package maple.calculator.pipeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class SnapshotChunkPipeline(
    private val properties: PipelineProperties,
    @Qualifier("vtDispatcher") private val vtDispatcher: CoroutineDispatcher,
) {

    private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
        "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
    }

    /**
     * 3-stage coroutine pipeline: source (Flow<String>) → parse → calculate → Flow<CalculationResult>.
     *
     * Stages:
     *  - Stage 1 (IO via VT): reads from `source` (gzip + disk), sends lines to internal lineChannel
     *  - Stage 2 (Default, `workerCount` parallel): parses each line, sends FlatItems to itemChannel
     *  - Stage 3 (Default, `workerCount` parallel): calculates each item, sends results to resultChannel
     *
     * Channels are closed by their producing stage. Result flow completes when all stages finish.
     */
    suspend fun run(
        source: Flow<String>,
        parse: suspend (String) -> SnapshotChunkParser.Outcome,
        calculate: suspend (FlatItem) -> CalculationResult,
    ): Flow<CalculationResult> = coroutineScope {
        // Unbounded channels: the consumer (resultWriter.write) is started AFTER this
        // coroutineScope returns, so a bounded resultChannel would block stage 3 on send
        // before the consumer exists, deadlocking the whole pipeline. Bounded would be
        // safe only if the consumer subscribed before the producer stages — they don't
        // here. Each chunk is bounded by recordCount (~500), so memory cost is small.
        val lineChannel = Channel<String>(Channel.UNLIMITED)
        val itemChannel = Channel<FlatItem>(Channel.UNLIMITED)
        val resultChannel = Channel<CalculationResult>(Channel.UNLIMITED)

        launch(vtDispatcher) {
            source.collect { line -> lineChannel.send(line) }
            lineChannel.close()
        }

        launch {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        for (line in lineChannel) {
                            when (val outcome = parse(line)) {
                                SnapshotChunkParser.Outcome.Skipped -> continue
                                is SnapshotChunkParser.Outcome.Parsed -> outcome.items.forEach { itemChannel.send(it) }
                            }
                        }
                    }
                }
            }
            itemChannel.close()
        }

        launch {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        for (item in itemChannel) {
                            resultChannel.send(calculate(item))
                        }
                    }
                }
            }
            resultChannel.close()
        }

        resultChannel.consumeAsFlow()
    }
}
