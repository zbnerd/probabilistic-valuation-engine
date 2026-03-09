package maple.expectation.domain.v2

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.LocalDateTime
import maple.expectation.error.exception.InvalidCharacterStateException
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity
import org.hibernate.annotations.NotFound
import org.hibernate.annotations.NotFoundAction

/**
 * GameCharacter 엔티티 (Rich Domain Model)
 *
 * <p>Issue #120: 캐릭터 상태 검증 로직 캡슐화
 *
 * <p><b>NOTE:</b> Legacy v2 entity - table name renamed to avoid conflict with clean architecture
 * {@code GameCharacterJpaEntity}
 *
 * <p><b>P2 Unit 3:</b> NamedEntityGraph for N+1 query prevention. Equipment association is loaded
 * eagerly when @EntityGraph is applied to repository methods.
 */
@Entity
@Table(name = "game_character_v2")
@NamedEntityGraph(
    name = "GameCharacter.withEquipment",
    attributeNodes = [NamedAttributeNode("equipment")],
)
class GameCharacter {

    companion object {
        private const val ACTIVE_DAYS_THRESHOLD = 30
        private const val BASIC_INFO_REFRESH_MINUTES = 15
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, unique = true)
    var userIgn: String? = null

    @Column(nullable = false, unique = true)
    var ocid: String? = null

    /** 월드명 (Nexon API character/basic에서 조회) */
    @Column(length = 50)
    var worldName: String? = null
        set(value) {
            validateOcidInternal(this.ocid)
            field = value
        }

    /** 직업명 (Nexon API character/basic에서 조회) */
    @Column(length = 50)
    var characterClass: String? = null
        set(value) {
            validateOcidInternal(this.ocid)
            field = value
        }

    /**
     * 캐릭터 이미지 URL (Nexon API character/basic에서 조회)
     *
     * <p>URL이 매우 길 수 있으므로 2048자로 설정
     */
    @Column(length = 2048)
    var characterImage: String? = null
        set(value) {
            validateOcidInternal(this.ocid)
            field = value
        }

    /**
     * 캐릭터 기본 정보 마지막 업데이트 시각
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신
     */
    var basicInfoUpdatedAt: LocalDateTime? = null
        set(value) {
            validateOcidInternal(this.ocid)
            field = value
        }

    /**
     * 장비 데이터 (LAZY 로딩)
     *
     * <p><b>P1 버그 수정 (PR #125 Codex 지적)</b>: {@code @JsonIgnore}로 JSON 응답에서 제외. 200-400KB blob이 API
     * 응답에 노출되면 보안 및 성능 문제 발생.
     */
    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    @JoinColumn(
        name = "ocid",
        referencedColumnName = "ocid",
        insertable = false,
        updatable = false,
        foreignKey = ForeignKey(value = ConstraintMode.NO_CONSTRAINT),
    )
    @NotFound(action = NotFoundAction.IGNORE)
    var equipment: CharacterEquipmentJpaEntity? = null
        set(value) {
            validateOcidInternal(this.ocid)
            field = value
        }

    @Version
    var version: Long? = null

    var likeCount: Long = 0L

    var updatedAt: LocalDateTime? = null

    private constructor()

    constructor(userIgn: String, ocid: String) {
        validateOcidInternal(ocid)
        this.userIgn = userIgn
        this.ocid = ocid
        this.likeCount = 0L
        this.updatedAt = LocalDateTime.now()
    }

    // ==================== Business Logic (기존) ====================

    fun like() {
        this.likeCount++
    }

    // ==================== Business Logic (Issue #120) ====================

    /**
     * 활성 캐릭터 여부 확인 (30일 이내 업데이트)
     *
     * @return 활성 상태면 true
     */
    fun isActive(): Boolean = this.updatedAt != null &&
        this.updatedAt!!.isAfter(LocalDateTime.now().minusDays(ACTIVE_DAYS_THRESHOLD.toLong()))

    /**
     * OCID 유효성 검증
     *
     * @throws InvalidCharacterStateException OCID가 null이거나 비어있는 경우
     */
    fun validateOcid() {
        validateOcidInternal(this.ocid)
    }

    /**
     * 캐릭터 기본 정보 갱신 필요 여부 확인
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신 필요
     *
     * @return 갱신 필요 시 true (worldName이 null이거나 15분 경과)
     */
    fun needsBasicInfoRefresh(): Boolean {
        // worldName이 없으면 갱신 필요
        if (this.worldName == null) {
            return true
        }
        // 마지막 업데이트 시각이 없거나 15분 이상 경과했으면 갱신 필요
        return this.basicInfoUpdatedAt == null ||
            this.basicInfoUpdatedAt!!.isBefore(
                LocalDateTime.now().minusMinutes(BASIC_INFO_REFRESH_MINUTES.toLong()),
            )
    }

    // ==================== Private Helpers ====================

    private fun validateOcidInternal(ocidValue: String?) {
        if (ocidValue == null || ocidValue.isBlank()) {
            throw InvalidCharacterStateException("OCID는 필수입니다")
        }
    }
}
