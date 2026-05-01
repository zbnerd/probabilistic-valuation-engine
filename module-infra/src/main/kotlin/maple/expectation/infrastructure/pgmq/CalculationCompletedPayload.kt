package maple.expectation.infrastructure.pgmq

data class CalculationCompletedPayload(
    val jobId: String,
    val characterId: String,
    val characterClass: String,
    val presetNo: Int,
    val gzipData: ByteArray,
    val hash: String,
    val originalSize: Int,
    val compressedSize: Int,
)
