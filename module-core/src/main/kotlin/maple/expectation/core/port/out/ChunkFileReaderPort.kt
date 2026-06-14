package maple.expectation.core.port.out

import maple.expectation.core.model.chunk.BasicRecord
import maple.expectation.core.model.chunk.GroupedEquipmentResult
import maple.expectation.core.model.chunk.OcidMapping

/**
 * Consolidated chunk file reader for the synchronizer pipeline.
 * Replaces 3 separate reader classes (BasicChunkFileReader, ResultFileReader,
 * OcidMappingFileReader) with a single port. All methods delegate to the
 * unified ObjectStorage (VS1).
 *
 * Implementations: DefaultChunkFileReader in module-synchronizer.
 */
interface ChunkFileReaderPort {
    fun readBasicChunk(objectKey: String): List<BasicRecord>
    fun readResultChunk(objectKey: String): List<GroupedEquipmentResult>
    fun readOcidMapping(manifestPath: String): List<OcidMapping>
}
