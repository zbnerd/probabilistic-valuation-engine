package maple.synchronizer.processor

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.synchronizer.builder.EquipmentDocumentBuilder
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.EquipmentDocumentPreparer
import maple.synchronizer.preparer.PreppedDocument
import org.springframework.stereotype.Component

data class TransformResult(
    val documentCount: Int,
    val itemCount: Long,
    val prepped: List<PreppedDocument>,
)

@Component
class ChunkDocumentTransformer(
    objectMapper: ObjectMapper,
    private val metrics: SynchronizerMetrics,
) {

    private val documentBuilder = EquipmentDocumentBuilder()
    private val preparer = EquipmentDocumentPreparer(objectMapper)

    fun transform(runId: String, chunkId: String, grouped: List<GroupedEquipmentResult>): TransformResult {
        val documents = timed(metrics.documentBuildTimer()) {
            grouped.map { g ->
                documentBuilder.build(runId, chunkId, g)
            }
        }

        val itemCount = grouped.sumOf { it.items.size.toLong() }
        val prepped = preparer.prepare(documents)

        return TransformResult(
            documentCount = documents.size,
            itemCount = itemCount,
            prepped = prepped,
        )
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
