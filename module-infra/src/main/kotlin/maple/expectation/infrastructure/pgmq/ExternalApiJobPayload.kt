package maple.expectation.infrastructure.pgmq

data class ExternalApiJobPayload(
    val jobId: String,
    val userIgn: String,
    val presetNo: Int,
)
