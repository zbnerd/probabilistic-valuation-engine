package maple.expectation.web.dto.response

import maple.expectation.core.domain.model.character.GameCharacter

/**
 * Character response DTO (Issue #128)
 *
 * <p>Prevents direct Entity exposure and optimizes API response size (350KB → 4KB)
 *
 * @param userIgn character nickname
 * @param ocid character unique ID
 * @param likeCount like count
 * @param worldName world name
 * @param characterClass class name
 * @param characterImage character image URL
 */
data class CharacterResponse(
    val userIgn: String?,
    val ocid: String?,
    val likeCount: Long?,
    val worldName: String?,
    val characterClass: String?,
    val characterImage: String?,
) {
    companion object {
        /**
         * Convert Domain Model to DTO
         *
         * @param character GameCharacter domain model
         * @return CharacterResponse DTO
         */
        @JvmStatic
        @JvmName("fromDomainModel")
        fun from(character: GameCharacter): CharacterResponse = CharacterResponse(
            userIgn = character.userIgn.value,
            ocid = character.characterId.value,
            likeCount = character.likeCount,
            worldName = character.worldName,
            characterClass = character.characterClass,
            characterImage = character.characterImage,
        )
    }
}
