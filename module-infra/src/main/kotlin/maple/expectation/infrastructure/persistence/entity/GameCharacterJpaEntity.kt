package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn

/**
 * JPA Entity for Game Character persistence.
 *
 * <p>This is a PERSISTENCE entity with JPA annotations. It belongs to infrastructure layer and
 * should only be used by repository implementations.
 *
 * <p><b>Important:</b> Business logic has been moved to
 * [maple.expectation.domain.model.character.GameCharacter]. This entity is purely for database
 * mapping.
 *
 * @see maple.expectation.domain.model.character.GameCharacter
 */
@Entity
@Table(name = "game_character")
open class GameCharacterJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(nullable = false, unique = true)
    open var userIgn: String? = null

    @Column(nullable = false, unique = true)
    open var ocid: String? = null

    @Column(length = 50)
    open var worldName: String? = null

    @Column(length = 50)
    open var characterClass: String? = null

    @Column(length = 2048)
    open var characterImage: String? = null

    @Column
    open var basicInfoUpdatedAt: LocalDateTime? = null

    @Version
    open var version: Long? = null

    open var likeCount: Long = 0L

    open var updatedAt: LocalDateTime? = null

    protected constructor()

    /**
     * Creates a new GameCharacterJpaEntity.
     *
     * @param userIgn in-game name
     * @param ocid character OCID
     */
    constructor(userIgn: UserIgn, ocid: CharacterId) {
        this.userIgn = userIgn.value
        this.ocid = ocid.value
        this.likeCount = 0L
        this.updatedAt = LocalDateTime.now()
    }

    /**
     * Converts JPA entity to domain model.
     *
     * <p><b>Note:</b> Equipment is stored separately in character_equipment table.
     *
     * @return GameCharacter domain instance
     */
    open fun toDomain(): GameCharacter {
        val userIgn = UserIgn.of(userIgn ?: "")
        val characterId = CharacterId.of(ocid ?: "")
        return GameCharacter.restore(
            id,
            characterId,
            userIgn,
            null,
            worldName,
            characterClass,
            characterImage,
            basicInfoUpdatedAt,
            likeCount,
            version,
            updatedAt ?: LocalDateTime.now(),
        )
    }

    companion object {
        /**
         * Converts domain model to JPA entity.
         *
         * @param domain GameCharacter domain instance
         * @return GameCharacterJpaEntity instance
         */
        fun fromDomain(domain: GameCharacter?): GameCharacterJpaEntity {
            if (domain == null) {
                throw IllegalArgumentException("Domain cannot be null")
            }

            return if (domain.id == null) {
                GameCharacterJpaEntity(domain.userIgn, domain.characterId)
            } else {
                val entity = GameCharacterJpaEntity()
                entity.id = domain.id
                entity.userIgn = domain.userIgn.value
                entity.ocid = domain.characterId.value
                entity.worldName = domain.worldName
                entity.characterClass = domain.characterClass
                entity.characterImage = domain.characterImage
                entity.basicInfoUpdatedAt = domain.basicInfoUpdatedAt
                entity.likeCount = domain.likeCount
                entity.version = domain.version
                entity.updatedAt = domain.updatedAt ?: LocalDateTime.now()
                entity
            }
        }
    }
}
