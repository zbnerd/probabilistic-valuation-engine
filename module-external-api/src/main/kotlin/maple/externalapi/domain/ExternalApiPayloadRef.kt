package maple.externalapi.domain

data class ExternalApiPayloadRef(
    val artifactUri: String,
    val sha256: String,
    val sizeBytes: Long,
    val contentType: String = "application/json",
    val schemaVersion: String = "v1",
)
