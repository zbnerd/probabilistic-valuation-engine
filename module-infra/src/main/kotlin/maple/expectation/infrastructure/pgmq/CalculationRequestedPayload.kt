package maple.expectation.infrastructure.pgmq

data class CalculationRequestedPayload(
    val jobId: String,
    val userIgn: String,
    val presetNo: Int,
    val characterId: String,
    val characterClass: String,
)
