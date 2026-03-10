package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant

/**
 * Nexon API 원본 데이터 엔티티 (ADR-006)
 *
 * <h3>역할</h3>
 * <p>Nexon Open API에서 수집한 원본 JSON 데이터를 PostgreSQL JSONB 컬럼에 저장
 *
 * <h3>테이블 구조</h3>
 * <ul>
 *   <li>id: 기본 키</li>
 *   <li>ocid: 캐릭터 OCID</li>
 *   <li>raw_jsonb: 원본 JSON 데이터 (JSONB)</li>
 *   <li>collected_at: 수집 시점</li>
 *   <li>status: 처리 상태 (PENDING, PROCESSED, FAILED)</li>
 * </ul>
 *
 * <h3>용도</h3>
 * <ul>
 *   <li>원본 데이터 보관 (감사 추적)</li>
 *   <li>재처리 지원</li>
 *   <li>데이터 분석</li>
 * </ul>
 */
@Entity
@Table(
    name = "nexon_raw_data",
    indexes = [
        Index(name = "idx_nexon_raw_ocid_status", columnList = "ocid, status"),
        Index(name = "idx_nexon_raw_collected_at", columnList = "collected_at"),
        Index(name = "idx_nexon_raw_status", columnList = "status"),
    ],
)
class NexonRawDataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, length = 100)
    var ocid: String? = null

    /**
     * 원본 JSON 데이터 (MySQL JSON)
     *
     * <p>Nexon Open API 응답 전체를 JSON으로 저장
     */
    @Column(nullable = false, columnDefinition = "json")
    var rawJsonb: String? = null

    @Column(nullable = false)
    var collectedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProcessStatus = ProcessStatus.PENDING

    @Column(updatable = false)
    var createdAt: Instant? = null

    var updatedAt: Instant? = null

    /**
     * 처리 상태
     */
    enum class ProcessStatus {
        /** 처리 대기 */
        PENDING,

        /** 처리 완료 */
        PROCESSED,

        /** 처리 실패 */
        FAILED,
    }

    /**
     * 처리 완료 마킹
     */
    fun markProcessed() {
        this.status = ProcessStatus.PROCESSED
        this.updatedAt = Instant.now()
    }

    /**
     * 처리 실패 마킹
     */
    fun markFailed() {
        this.status = ProcessStatus.FAILED
        this.updatedAt = Instant.now()
    }

    @PrePersist
    fun onPrePersist() {
        val now = Instant.now()
        this.createdAt = now
        this.updatedAt = now
        if (collectedAt == null) {
            collectedAt = now
        }
    }

    @PreUpdate
    fun onPreUpdate() {
        this.updatedAt = Instant.now()
    }

    companion object {
        /**
         * 팩토리 메서드
         *
         * @param ocid 캐릭터 OCID
         * @param rawJsonb 원본 JSON 데이터
         * @return NexonRawDataEntity 인스턴스
         */
        fun create(ocid: String, rawJsonb: String): NexonRawDataEntity {
            val entity = NexonRawDataEntity()
            entity.ocid = ocid
            entity.rawJsonb = rawJsonb
            entity.collectedAt = Instant.now()
            entity.status = ProcessStatus.PENDING
            return entity
        }
    }
}
