package maple.expectation.infrastructure.external.dto.v2

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Nexon API character/list 응답 DTO
 *
 * <p>API: GET /maplestory/v1/character/list
 *
 * <p>응답 구조:
 *
 * <pre>
 * {
 *   "account_list": [
 *     {
 *       "account_id": "...",
 *       "character_list": [...]
 *     }
 *   ]
 * }
 * </pre>
 */
data class CharacterListResponse(
    @JsonProperty("account_list")
    val accountList: List<AccountInfo>? = null,
) {

    /** 모든 계정의 캐릭터 목록을 평탄화하여 반환 */
    fun getAllCharacters(): List<CharacterInfo> {
        if (accountList.isNullOrEmpty()) {
            return emptyList()
        }
        return accountList
            .filter { it.characterList != null }
            .flatMap { it.characterList!!.toList() }
    }

    /** 계정 정보 */
    data class AccountInfo(
        @JsonProperty("account_id")
        val accountId: String? = null,

        @JsonProperty("character_list")
        val characterList: List<CharacterInfo>? = null,
    )

    /** 개별 캐릭터 정보 */
    data class CharacterInfo(
        @JsonProperty("ocid")
        val ocid: String? = null,

        @JsonProperty("character_name")
        val characterName: String? = null,

        @JsonProperty("world_name")
        val worldName: String? = null,

        @JsonProperty("character_class")
        val characterClass: String? = null,

        @JsonProperty("character_level")
        val characterLevel: Int = 0,
    )
}
