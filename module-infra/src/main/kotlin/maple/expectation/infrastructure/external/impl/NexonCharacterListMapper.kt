package maple.expectation.infrastructure.external.impl

import maple.expectation.infrastructure.external.dto.v2.CharacterListResponse
import maple.nexon.client.byok.NexonCharacterList
import org.springframework.stereotype.Component

@Component
class NexonCharacterListMapper {
    fun toLegacy(characterList: NexonCharacterList): CharacterListResponse = CharacterListResponse(
        accountList = characterList.accounts.map { account ->
            CharacterListResponse.AccountInfo(
                accountId = account.accountId,
                characterList = account.characters.map { character ->
                    CharacterListResponse.CharacterInfo(
                        ocid = character.ocid,
                        characterName = character.characterName,
                        worldName = character.worldName,
                        characterClass = character.characterClass,
                        characterLevel = character.characterLevel,
                    )
                },
            )
        },
    )
}
