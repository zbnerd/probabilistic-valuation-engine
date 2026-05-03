package maple.expectation.infrastructure.persistence.repository

import java.time.Instant
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.NexonRawDataEntity
import maple.expectation.infrastructure.persistence.jpa.NexonRawDataJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Nexon 원본 데이터 저장소 (ADR-006)
 *
 * <h3>역할</h3>
 * <p>Nexon Open API에서 수집한 원본 JSON 데이터의 CRUD 연산 수행
 *
 * <h3>Zero Try-Catch</h3>
 * <p>모든 예외 처리는 LogicExecutor에 위임 (Section 12 준수)
 *
 * @see NexonRawDataEntity
 * @see NexonRawDataJpaRepository
 */
@Component
class NexonRawDataStore(
    private val jpaRepository: NexonRawDataJpaRepository,
    private val executor: LogicExecutor,
) {

    /**
     * 원본 데이터 저장
     *
     * @param ocid 캐릭터 OCID
     * @param rawJsonb 원본 JSON 데이터
     * @return 저장된 엔티티
     */
    fun save(ocid: String, rawJsonb: String): NexonRawDataEntity {
        val context = TaskContext.of("NexonRawData", "Save", ocid)

        return executor.execute(
            {
                val entity = NexonRawDataEntity.create(ocid, rawJsonb)
                jpaRepository.save(entity)
            },
            context,
        )
    }

    /**
     * OCID로 최신 원본 데이터 조회
     *
     * @param ocid 캐릭터 OCID
     * @return 최신 원본 데이터 또는 null
     */
    fun findLatestByOcid(ocid: String): NexonRawDataEntity? {
        val context = TaskContext.of("NexonRawData", "FindLatest", ocid)

        return executor.executeOrDefault(
            { jpaRepository.findLatestByOcid(ocid) },
            null,
            context,
        )
    }

    /**
     * PENDING 상태인 원본 데이터 목록 조회
     *
     * @param threshold 기준 시각
     * @param limit 최대 조회 수
     * @return PENDING 상태인 원본 데이터 목록
     */
    fun findPendingBefore(threshold: Instant, limit: Int): List<NexonRawDataEntity> {
        val context = TaskContext.of("NexonRawData", "FindPending", "limit=$limit")

        return executor.executeOrDefault(
            { jpaRepository.findPendingBefore(threshold, limit) },
            emptyList(),
            context,
        )
    }

    /**
     * 일괄 처리 상태 업데이트
     *
     * @param ids 대상 ID 목록
     * @param status 처리 상태
     */
    fun updateStatusByIds(ids: List<Long>, status: NexonRawDataEntity.ProcessStatus) {
        val context = TaskContext.of("NexonRawData", "UpdateStatus", "count=${ids.size}")

        executor.executeVoidJava(
            { jpaRepository.updateStatusByIds(ids, status) },
            context,
        )
    }

    /**
     * 처리 완료 마킹
     *
     * @param id 데이터 ID
     */
    fun markProcessed(id: Long) {
        val context = TaskContext.of("NexonRawData", "MarkProcessed", "id=$id")

        executor.executeVoidJava(
            {
                val entity = jpaRepository.findById(id).orElseGet { null }
                entity?.markProcessed()
                entity?.let { jpaRepository.save(it) }
            },
            context,
        )
    }

    /**
     * 처리 실패 마킹
     *
     * @param id 데이터 ID
     */
    fun markFailed(id: Long) {
        val context = TaskContext.of("NexonRawData", "MarkFailed", "id=$id")

        executor.executeVoidJava(
            {
                val entity = jpaRepository.findById(id).orElseGet { null }
                entity?.markFailed()
                entity?.let { jpaRepository.save(it) }
            },
            context,
        )
    }

    /**
     * 오래된 데이터 삭제 (보관 정책)
     *
     * @param before 기준 시각
     */
    fun deleteOlderThan(before: Instant) {
        val context = TaskContext.of("NexonRawData", "DeleteOld", "before=$before")

        executor.executeVoidJava(
            { jpaRepository.deleteOlderThan(before) },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NexonRawDataStore::class.java)
    }
}

/**
 * Nexon 원본 데이터 처리 예외
 */
class NexonRawDataException(message: String, cause: Throwable? = null) : ServerBaseException(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message)
