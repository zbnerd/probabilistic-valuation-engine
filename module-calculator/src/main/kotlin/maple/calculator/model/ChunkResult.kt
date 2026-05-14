package maple.calculator.model

data class ChunkResult(
    val recordCount: Int,
    val successCount: Int,
    val totalItems: Int,
    val calculatedCount: Int,
    val errorCount: Int,
    val resultObjectKey: String,
    val resultCount: Int,
    val resultUncompressedBytes: Long,
    val resultCompressedBytes: Long,
)
