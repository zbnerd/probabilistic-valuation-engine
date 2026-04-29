package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import maple.expectation.infrastructure.persistence.repository.CalculationResultRepository
import org.springframework.stereotype.Component

@Component
class CalculationResultPortAdapter(
    private val repo: CalculationResultRepository,
) : CalculationResultPort {

    override fun save(result: CalculationResultData): CalculationResultData {
        val existing = repo.findByJobId(result.jobId)
        val entity = if (existing != null && existing.hash == result.hash) {
            existing
        } else {
            repo.save(
                CalculationResultEntity(
                    resultId = result.resultId,
                    jobId = result.jobId,
                    characterClass = result.characterClass,
                    presetNo = result.presetNo,
                    schemaVersion = result.schemaVersion,
                    contentType = result.contentType,
                    contentEncoding = result.contentEncoding,
                    responseBody = result.responseBody,
                    originalSize = result.originalSize,
                    compressedSize = result.compressedSize,
                    hash = result.hash,
                    status = result.status,
                ),
            )
        }
        return CalculationResultData(
            resultId = entity.resultId,
            jobId = entity.jobId,
            characterClass = entity.characterClass,
            presetNo = entity.presetNo,
            schemaVersion = entity.schemaVersion,
            contentType = entity.contentType,
            contentEncoding = entity.contentEncoding,
            responseBody = entity.responseBody,
            originalSize = entity.originalSize,
            compressedSize = entity.compressedSize,
            hash = entity.hash ?: "",
            status = entity.status,
        )
    }

    override fun findByJobId(jobId: UUID): CalculationResultData? = repo.findByJobId(jobId)?.toData()

    override fun existsByJobId(jobId: UUID): Boolean = repo.existsByJobId(jobId)

    private fun CalculationResultEntity.toData() = CalculationResultData(
        resultId = resultId, jobId = jobId, characterClass = characterClass,
        presetNo = presetNo, schemaVersion = schemaVersion,
        contentType = contentType, contentEncoding = contentEncoding,
        responseBody = responseBody, originalSize = originalSize,
        compressedSize = compressedSize, hash = hash ?: "", status = status,
    )
}
