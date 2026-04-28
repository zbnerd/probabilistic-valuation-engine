package maple.expectation.core.port.out

import java.util.UUID

data class CalculationResultData(
    val resultId: UUID,
    val jobId: UUID,
    val characterClass: String?,
    val presetNo: Int,
    val schemaVersion: Int,
    val contentType: String,
    val contentEncoding: String,
    val responseBody: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val hash: String,
    val status: String
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

interface CalculationResultPort {
    fun save(result: CalculationResultData): CalculationResultData
    fun findByJobId(jobId: UUID): CalculationResultData?
    fun existsByJobId(jobId: UUID): Boolean
}
