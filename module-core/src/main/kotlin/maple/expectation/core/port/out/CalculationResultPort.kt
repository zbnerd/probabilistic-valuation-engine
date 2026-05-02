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
    val status: String,
    val totalExpectedCost: Long? = null,
    val maxPresetNo: Int? = null,
    val presetsJson: String? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class CalculationResultLight(
    val jobId: UUID,
    val characterClass: String?,
    val presetNo: Int,
    val totalExpectedCost: Long?,
    val maxPresetNo: Int?,
    val presetsJson: String?,
)

interface CalculationResultPort {
    fun save(result: CalculationResultData): CalculationResultData
    fun saveIfAbsent(result: CalculationResultData): Boolean
    fun findByJobId(jobId: UUID): CalculationResultData?
    fun findByJobIds(jobIds: List<UUID>): List<CalculationResultData>
    fun findByJobIdsLight(jobIds: List<UUID>): List<CalculationResultLight>
    fun findByJobIdsWithBody(jobIds: List<UUID>): List<CalculationResultData>
    fun existsByJobId(jobId: UUID): Boolean
}
