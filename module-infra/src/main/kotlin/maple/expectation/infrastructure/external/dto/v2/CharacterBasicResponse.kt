package maple.expectation.infrastructure.external.dto.v2

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Nexon API 캐릭터 기본 정보 응답 DTO
 *
 * <p>Endpoint: GET /maplestory/v1/character/basic
 *
 * @see <a href="https://openapi.nexon.com/ko/game/maplestory/?id=22">Nexon Open API</a>
 */
data class CharacterBasicResponse(
    @JsonProperty("character_name")
    val characterName: String? = null,

    @JsonProperty("world_name")
    val worldName: String? = null,

    @JsonProperty("character_class")
    val characterClass: String? = null,

    @JsonProperty("character_level")
    val characterLevel: Int = 0,

    @JsonProperty("character_image")
    val characterImage: String? = null,

    @JsonProperty("character_guild_name")
    val guildName: String? = null,
)
