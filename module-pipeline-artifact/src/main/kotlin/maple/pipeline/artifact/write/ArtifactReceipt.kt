package maple.pipeline.artifact.write

import maple.pipeline.artifact.identity.ArtifactKey

data class ArtifactReceipt(
    val key: ArtifactKey,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val contentSha256: String,
    val backendTag: String?,
)
