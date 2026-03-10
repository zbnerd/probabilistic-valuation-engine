package maple.expectation.infrastructure.persistence.jpa

import java.time.Instant
import maple.expectation.infrastructure.persistence.entity.NexonRawDataEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/**
 * Spring Data JPA Repository for NexonRawData
 *
 * @see NexonRawDataEntity
 */
interface NexonRawDataJpaRepository : JpaRepository<NexonRawDataEntity, Long> {

    /**
     * OCID로 가장 최근 원본 데이터 조회
     *
     * @param ocid 캐릭터 OCID
     * @return 최근 원본 데이터 또는 null
     */
    @Query(
        """
        SELECT n FROM NexonRawDataEntity n
        WHERE n.ocid = :ocid
        ORDER BY n.collectedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestByOcid(ocid: String): NexonRawDataEntity?

    /**
     * PENDING 상태인 원본 데이터 목록 조회
     *
     * @param threshold 기준 시각
     * @param limit 최대 조회 수
     * @return PENDING 상태인 원본 데이터 목록
     */
    @Query(
        """
        SELECT n FROM NexonRawDataEntity n
        WHERE n.status = 'PENDING'
        AND n.collectedAt < :threshold
        ORDER BY n.collectedAt ASC
        LIMIT :limit
        """,
    )
    fun findPendingBefore(threshold: Instant, limit: Int): List<NexonRawDataEntity>

    /**
     * 일괄 처리 상태 업데이트
     *
     * @param ids 대상 ID 목록
     * @param status 처리 상태
     */
    @Modifying
    @Query("UPDATE NexonRawDataEntity n SET n.status = :status, n.updatedAt = :updatedAt WHERE n.id IN :ids")
    fun updateStatusByIds(ids: List<Long>, status: NexonRawDataEntity.ProcessStatus, updatedAt: Instant = Instant.now())

    /**
     * 오래된 데이터 삭제 (보관 정책)
     *
     * @param before 기준 시각
     */
    @Modifying
    @Query("DELETE FROM NexonRawDataEntity n WHERE n.collectedAt < :before")
    fun deleteOlderThan(before: Instant)

    /**
     * OCID와 상태로 데이터 개수 조회
     *
     * @param ocid 캐릭터 OCID
     * @param status 처리 상태
     * @return 데이터 개수
     */
    fun countByOcidAndStatus(ocid: String, status: NexonRawDataEntity.ProcessStatus): Long
}
