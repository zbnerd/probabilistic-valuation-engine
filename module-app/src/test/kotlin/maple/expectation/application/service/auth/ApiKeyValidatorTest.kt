package maple.expectation.application.service.auth

import java.util.Optional
import maple.expectation.error.exception.CharacterNotOwnedException
import maple.expectation.error.exception.InvalidApiKeyException
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.expectation.infrastructure.external.dto.v2.CharacterListResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
@DisplayName("ApiKeyValidator ownership verification tests")
class ApiKeyValidatorTest {

    @Mock
    private lateinit var nexonAuthClient: NexonAuthClient

    @InjectMocks
    private lateinit var validator: ApiKeyValidator

    @Test
    @DisplayName("account_id가 blank면 InvalidApiKeyException을 던진다")
    fun `validateAndVerifyOwnership should fail when account id is blank`() {
        whenever(nexonAuthClient.getCharacterList("api-key")).thenReturn(
            Optional.of(
                CharacterListResponse(
                    accountList = listOf(
                        CharacterListResponse.AccountInfo(
                            accountId = "  ",
                            characterList = listOf(character(name = "Hero", ocid = "ocid-1")),
                        ),
                    ),
                ),
            ),
        )

        assertThatThrownBy { validator.validateAndVerifyOwnership("api-key", "Hero") }
            .isInstanceOf(InvalidApiKeyException::class.java)
            .hasMessageContaining("account_id")
    }

    @Test
    @DisplayName("유효한 OCID가 하나도 없으면 InvalidApiKeyException을 던진다")
    fun `validateAndVerifyOwnership should fail when no valid ocids exist`() {
        whenever(nexonAuthClient.getCharacterList("api-key")).thenReturn(
            Optional.of(
                CharacterListResponse(
                    accountList = listOf(
                        CharacterListResponse.AccountInfo(
                            accountId = "account-1",
                            characterList = listOf(character(name = "Hero", ocid = " ")),
                        ),
                    ),
                ),
            ),
        )

        assertThatThrownBy { validator.validateAndVerifyOwnership("api-key", "Hero") }
            .isInstanceOf(InvalidApiKeyException::class.java)
            .hasMessageContaining("OCIDs")
    }

    @Test
    @DisplayName("userIgn 비교 시 공백/대소문자를 무시하고 소유권을 인정한다")
    fun `validateAndVerifyOwnership should match ownership with trimmed case-insensitive ign`() {
        whenever(nexonAuthClient.getCharacterList("api-key")).thenReturn(
            Optional.of(
                CharacterListResponse(
                    accountList = listOf(
                        CharacterListResponse.AccountInfo(
                            accountId = "account-1",
                            characterList = listOf(character(name = "  hero  ", ocid = "ocid-1")),
                        ),
                    ),
                ),
            ),
        )

        val result = validator.validateAndVerifyOwnership("api-key", " HERO ")

        assertThat(result.accountId).isEqualTo("account-1")
        assertThat(result.myOcids).containsExactly("ocid-1")
    }

    @Test
    @DisplayName("소유하지 않은 캐릭터면 CharacterNotOwnedException을 던진다")
    fun `validateAndVerifyOwnership should fail when character is not owned`() {
        whenever(nexonAuthClient.getCharacterList("api-key")).thenReturn(
            Optional.of(
                CharacterListResponse(
                    accountList = listOf(
                        CharacterListResponse.AccountInfo(
                            accountId = "account-1",
                            characterList = listOf(character(name = "Other", ocid = "ocid-1")),
                        ),
                    ),
                ),
            ),
        )

        assertThatThrownBy { validator.validateAndVerifyOwnership("api-key", "Hero") }
            .isInstanceOf(CharacterNotOwnedException::class.java)
    }

    private fun character(name: String, ocid: String): CharacterListResponse.CharacterInfo =
        CharacterListResponse.CharacterInfo(
            characterName = name,
            ocid = ocid,
        )
}
