package maple.nexon.client.byok

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import maple.nexon.client.failure.DecodeFailure

class CharacterListDecoder(
    private val objectMapper: ObjectMapper,
) {
    fun decode(bytes: ByteArray): NexonCharacterList = runCatching {
        val wire = objectMapper.readValue(bytes, CharacterListWire::class.java)
        NexonCharacterList(
            accounts = wire.accountList.orEmpty().map { account ->
                NexonAccount(
                    accountId = account.accountId,
                    characters = account.characterList.orEmpty().map { character ->
                        NexonCharacter(
                            ocid = character.ocid,
                            characterName = character.characterName,
                            worldName = character.worldName,
                            characterClass = character.characterClass,
                            characterLevel = character.characterLevel ?: 0,
                        )
                    },
                )
            },
        )
    }.getOrElse {
        throw DecodeFailure(CHARACTER_LIST_REQUEST)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CharacterListWire(
    @JsonProperty("account_list")
    val accountList: List<AccountWire>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AccountWire(
    @JsonProperty("account_id")
    val accountId: String? = null,
    @JsonProperty("character_list")
    val characterList: List<CharacterWire>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CharacterWire(
    @JsonProperty("ocid")
    val ocid: String? = null,
    @JsonProperty("character_name")
    val characterName: String? = null,
    @JsonProperty("world_name")
    val worldName: String? = null,
    @JsonProperty("character_class")
    val characterClass: String? = null,
    @JsonProperty("character_level")
    val characterLevel: Int? = null,
)
