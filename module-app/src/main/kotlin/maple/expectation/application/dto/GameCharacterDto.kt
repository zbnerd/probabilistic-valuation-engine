package maple.expectation.application.dto

import java.time.LocalDateTime
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn

/**
 * GameCharacter Data Transfer Object
 */
data class GameCharacterDto(
    var userIgn: String? = null,
    var ocid: String? = null,
    var worldName: String? = null,
    var characterClass: String? = null,
    var characterImage: String? = null,
    var basicInfoUpdatedAt: LocalDateTime? = null,
    var likeCount: Long? = null,
) : BaseDto() {

    var id: Long? = null

    companion object {
        @JvmStatic
        fun from(entity: GameCharacter): GameCharacterDto = GameCharacterDto(
            userIgn = entity.userIgn.value,
            ocid = entity.characterId.value,
            worldName = entity.worldName,
            characterClass = entity.characterClass,
            characterImage = entity.characterImage,
            basicInfoUpdatedAt = entity.basicInfoUpdatedAt,
            likeCount = entity.likeCount,
        ).apply {
            id = entity.id
            updatedAt = entity.updatedAt
        }

        @JvmStatic
        fun forCreation(userIgn: String, ocid: String): GameCharacterDto = GameCharacterDto(
            userIgn = userIgn,
            ocid = ocid,
            likeCount = 0L,
        ).apply {
            initTimestamps()
        }
    }

    fun toEntity(): GameCharacter {
        val userIgnValue = userIgn?.let { UserIgn.of(it) }
        val characterIdValue = ocid?.let { CharacterId.of(it) }

        return GameCharacter.restore(
            id,
            characterIdValue ?: throw IllegalStateException("characterId must not be null"),
            userIgnValue ?: throw IllegalStateException("userIgn must not be null"),
            null, // equipment not set from DTO
            worldName,
            characterClass,
            characterImage,
            basicInfoUpdatedAt,
            likeCount ?: 0L,
            version,
            updatedAt ?: LocalDateTime.now(),
        )
    }
}
