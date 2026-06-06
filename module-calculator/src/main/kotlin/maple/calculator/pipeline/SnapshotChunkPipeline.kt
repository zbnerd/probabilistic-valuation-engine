package maple.calculator.pipeline

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
import org.springframework.stereotype.Component

@Component
class SnapshotChunkPipeline(
    private val properties: PipelineProperties,
) {

    private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
        "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
    }

    /**
     * 3-stage coroutine pipeline: source (Flow<String>) → parse → calculate → Flow<CalculationResult>.
     *
     * Stages:
     *  - Stage 1 (IO): reads from `source`, sends lines to internal lineChannel
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
        val lineChannel = Channel<String>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val resultChannel = Channel<CalculationResult>(properties.channelCapacity)

        launch(Dispatchers.IO) {
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
