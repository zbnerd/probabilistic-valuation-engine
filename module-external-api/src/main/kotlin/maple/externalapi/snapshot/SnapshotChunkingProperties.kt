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
        val rankingOverall: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 5000),
    )

    data class EndpointChunkConfig(
        val maxRecords: Int = 1000,
        val maxUncompressedBytes: Long = 128L * 1024 * 1024, // 128 MB hard cap per uncompressed chunk
        val maxChunkAgeMs: Long = 1000L, // ADR-744: idle-tick flush threshold
    )

    fun configFor(endpoint: String): EndpointChunkConfig = when (endpoint) {
        "character-basic" -> chunk.characterBasic
        "item-equipment" -> chunk.itemEquipment
        "ranking-overall" -> chunk.rankingOverall
        else -> EndpointChunkConfig()
    }
}
