package maple.externalapi.snapshot

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external-api.snapshot")
data class SnapshotChunkingProperties(
    val chunk: ChunkConfig = ChunkConfig(),
    val queueCapacity: Int = 1000,
) {
    data class ChunkConfig(
        val characterBasic: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 2000),
        val itemEquipment: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 500),
    )

    data class EndpointChunkConfig(
        val maxRecords: Int = 1000,
        val maxUncompressedBytes: Long = 134217728L,
    )

    fun configFor(endpoint: String): EndpointChunkConfig = when (endpoint) {
        "character-basic" -> chunk.characterBasic
        "item-equipment" -> chunk.itemEquipment
        else -> EndpointChunkConfig()
    }
}
