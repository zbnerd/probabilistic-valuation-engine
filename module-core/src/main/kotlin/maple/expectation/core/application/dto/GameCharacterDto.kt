package maple.expectation.core.application.dto

import maple.expectation.domain.model.character.CharacterId
import maple.expectation.domain.model.character.GameCharacter
import maple.expectation.domain.model.character.UserIgn
import java.time.LocalDateTime

/**
 * GameCharacter Data Transfer Object
 */
data class GameCharacterDto(
    val id: Long? = null,
    val userIgn: String,
    val ocid: String,
    val worldName: String,
    val characterClass: String,
    val characterImage: String,
    val basicInfoUpdatedAt: LocalDateTime?,
    val likeCount: Long,
    override val updatedAt: LocalDateTime?
) : BaseDto() {

    companion object {
        @JvmStatic
        fun from(entity: GameCharacter): GameCharacterDto {
            return GameCharacterDto(
                id = entity.id,
                userIgn = entity.userIgn.value,
                ocid = entity.characterId.value,
                worldName = entity.worldName ?: "",
                characterClass = entity.characterClass ?: "",
                characterImage = entity.characterImage ?: "",
                basicInfoUpdatedAt = entity.basicInfoUpdatedAt,
                likeCount = entity.likeCount,
                updatedAt = entity.updatedAt
            )
        }

        @JvmStatic
        fun forCreation(
            userIgn: String,
            ocid: String,
            worldName: String,
            characterClass: String
        ): GameCharacterDto {
            val dto = GameCharacterDto(
                userIgn = userIgn,
                ocid = ocid,
                worldName = worldName,
                characterClass = characterClass,
                characterImage = "",
                basicInfoUpdatedAt = null,
                likeCount = 0,
                updatedAt = null
            )
            dto.initTimestamps()
            return dto
        }

        @JvmStatic
        fun toCharacterId(ocid: String): CharacterId {
            return CharacterId(ocid)
        }

        @JvmStatic
        fun toUserIgn(userIgn: String): UserIgn {
            return UserIgn(userIgn)
        }
    }
}
