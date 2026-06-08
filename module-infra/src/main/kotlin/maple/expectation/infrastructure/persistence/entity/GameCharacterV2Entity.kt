package maple.expectation.infrastructure.persistence.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.LocalDateTime
import maple.expectation.infrastructure.persistence.entity.CharacterEquipmentJpaEntity
import org.hibernate.annotations.NotFound
import org.hibernate.annotations.NotFoundAction

/**
 * GameCharacter v2 엔티티 (JPA 매핑 전용)
 *
 * <p>비즈니스 로직 제거. table=game_character_v2 유지.
 *
 * <p>Issue #896: v2 패키지에서 infrastructure/persistence/entity/로 이관.
 */
@Entity
@Table(name = "game_character_v2")
@NamedEntityGraph(
    name = "GameCharacter.withEquipment",
    attributeNodes = [NamedAttributeNode("equipment")],
)
class GameCharacterV2Entity {

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

    /** 직업명 (Nexon API character/basic에서 조회) */
    @Column(length = 50)
    var characterClass: String? = null

    /**
     * 캐릭터 이미지 URL (Nexon API character/basic에서 조회)
     *
     * <p>URL이 매우 길 수 있으므로 2048자로 설정
     */
    @Column(length = 2048)
    var characterImage: String? = null

    /**
     * 캐릭터 기본 정보 마지막 업데이트 시각
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신
     */
    var basicInfoUpdatedAt: LocalDateTime? = null

    /**
     * 장비 데이터 (LAZY 로딩)
     *
     * <p><b>P1 버그 수정 (PR #125 Codex 지적)</b>: {@code @JsonIgnore}로 JSON 응답에서 제외.
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

    @Version
    var version: Long? = null

    var likeCount: Long = 0L

    var updatedAt: LocalDateTime? = null

    private constructor()

    constructor(userIgn: String, ocid: String) {
        this.userIgn = userIgn
        this.ocid = ocid
        this.likeCount = 0L
        this.updatedAt = LocalDateTime.now()
    }
}
