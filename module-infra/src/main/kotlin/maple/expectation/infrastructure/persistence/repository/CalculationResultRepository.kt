package maple.expectation.infrastructure.persistence.repository

import java.util.UUID
import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CalculationResultRepository : JpaRepository<CalculationResultEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationResultEntity?
    fun findByJobIdIn(jobIds: Collection<UUID>): List<CalculationResultEntity>
    fun existsByJobId(jobId: UUID): Boolean

    @Modifying
    @Query(
        value = """
            INSERT INTO calculation_results (result_id, job_id, character_class, preset_no, schema_version,
                content_type, content_encoding, response_body, original_size, compressed_size, hash, status, created_at)
            VALUES (:resultId, :jobId, :characterClass, :presetNo, :schemaVersion,
                :contentType, :contentEncoding, :responseBody, :originalSize, :compressedSize, :hash, :status, now())
            ON CONFLICT (job_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("resultId") resultId: UUID,
        @Param("jobId") jobId: UUID,
        @Param("characterClass") characterClass: String?,
        @Param("presetNo") presetNo: Int,
        @Param("schemaVersion") schemaVersion: Int,
        @Param("contentType") contentType: String,
        @Param("contentEncoding") contentEncoding: String,
        @Param("responseBody") responseBody: ByteArray,
        @Param("originalSize") originalSize: Int,
        @Param("compressedSize") compressedSize: Int,
        @Param("hash") hash: String,
        @Param("status") status: String,
    ): Int
}
