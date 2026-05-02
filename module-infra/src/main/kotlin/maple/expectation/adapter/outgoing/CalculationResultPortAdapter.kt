package maple.expectation.adapter.outgoing

import java.util.UUID
import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultLight
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import maple.expectation.infrastructure.persistence.repository.CalculationResultRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CalculationResultPortAdapter(
    private val repo: CalculationResultRepository,
    private val jdbc: JdbcTemplate,
) : CalculationResultPort {

    @Transactional(value = "transactionManager", readOnly = false)
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
                    totalExpectedCost = result.totalExpectedCost,
                    maxPresetNo = result.maxPresetNo,
                    presets = result.presetsJson,
                ),
            )
        }
        return entity.toData()
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findByJobId(jobId: UUID): CalculationResultData? = repo.findByJobId(jobId)?.toData()

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findByJobIds(jobIds: List<UUID>): List<CalculationResultData> {
        if (jobIds.isEmpty()) return emptyList()
        return repo.findByJobIdIn(jobIds).map { it.toData() }
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findByJobIdsLight(jobIds: List<UUID>): List<CalculationResultLight> {
        if (jobIds.isEmpty()) return emptyList()
        val placeholders = jobIds.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT job_id, character_class, preset_no, total_expected_cost, max_preset_no, presets FROM calculation_results WHERE job_id IN ($placeholders)",
            { rs, _ ->
                val tec = rs.getLong("total_expected_cost")
                val mpn = rs.getInt("max_preset_no")
                CalculationResultLight(
                    jobId = rs.getObject("job_id", UUID::class.java),
                    characterClass = rs.getString("character_class"),
                    presetNo = rs.getInt("preset_no"),
                    totalExpectedCost = if (rs.wasNull()) null else tec,
                    maxPresetNo = if (rs.wasNull()) null else mpn,
                    presetsJson = rs.getString("presets"),
                )
            },
            *jobIds.toTypedArray(),
        )
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun findByJobIdsWithBody(jobIds: List<UUID>): List<CalculationResultData> {
        if (jobIds.isEmpty()) return emptyList()
        return repo.findByJobIdIn(jobIds).map { it.toData() }
    }

    @Transactional(value = "transactionManager", readOnly = true)
    override fun existsByJobId(jobId: UUID): Boolean = repo.existsByJobId(jobId)

    @Transactional(value = "transactionManager", readOnly = false)
    override fun saveIfAbsent(result: CalculationResultData): Boolean = repo.insertIfAbsent(
        result.resultId,
        result.jobId,
        result.characterClass,
        result.presetNo,
        result.schemaVersion,
        result.contentType,
        result.contentEncoding,
        result.responseBody,
        result.originalSize,
        result.compressedSize,
        result.hash,
        result.status,
        result.totalExpectedCost,
        result.maxPresetNo,
        result.presetsJson,
    ) > 0

    private fun CalculationResultEntity.toData() = CalculationResultData(
        resultId = resultId, jobId = jobId, characterClass = characterClass,
        presetNo = presetNo, schemaVersion = schemaVersion,
        contentType = contentType, contentEncoding = contentEncoding,
        responseBody = responseBody, originalSize = originalSize,
        compressedSize = compressedSize, hash = hash ?: "", status = status,
        totalExpectedCost = totalExpectedCost, maxPresetNo = maxPresetNo,
        presetsJson = presets,
    )
}
