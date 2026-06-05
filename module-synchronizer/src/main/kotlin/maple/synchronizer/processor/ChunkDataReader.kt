package maple.synchronizer.processor

import io.micrometer.core.instrument.Timer
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.resolver.OcidUserIgnResolver
import maple.synchronizer.storage.ResultFileReader
import org.springframework.stereotype.Component

@Component
class ChunkDataReader(
    private val resultFileReader: ResultFileReader,
    private val ocidUserIgnResolver: OcidUserIgnResolver,
    private val metrics: SynchronizerMetrics,
) {

    fun read(objectKey: String): List<GroupedEquipmentResult> {
        val grouped = timed(metrics.fileReadTimer()) {
            resultFileReader.readAndGroupByCompositeKey(objectKey)
        }

        val ocids = grouped.map { it.ocid }.toSet()
        val ocidToUserIgn = ocidUserIgnResolver.resolve(ocids)

        return grouped.map { it.copy(userIgn = ocidToUserIgn[it.ocid]) }
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
