package maple.expectation.application.service.auth

import maple.expectation.error.exception.CharacterNotOwnedException
import maple.expectation.error.exception.InvalidApiKeyException
import maple.expectation.infrastructure.external.NexonAuthClient
import maple.expectation.infrastructure.external.dto.v2.CharacterListResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * API Key 검증 및 캐릭터 소유권 확인 서비스
 *
 * 책임 (Single Responsibility Principle):
 * - Nexon API Key 유효성 검증
 * - 캐릭터 소유권 확인 (userIgn이 캐릭터 목록에 있는지)
 * - 사용자의 모든 캐릭터 OCID 수집
 */
@Service
class ApiKeyValidator(
    private val nexonAuthClient: NexonAuthClient,
) {

    /**
     * API Key를 검증하고 캐릭터 목록을 조회합니다.
     *
     * @param apiKey Nexon API Key
     * @return 캐릭터 목록 응답
     * @throws InvalidApiKeyException API Key가 유효하지 않은 경우
     */
    fun validateApiKey(apiKey: String): CharacterListResponse {
        log.debug("Validating API key")
        return nexonAuthClient.getCharacterList(apiKey).orElseThrow(::InvalidApiKeyException)
    }

    /**
     * 캐릭터 소유권을 확인합니다.
     *
     * @param userIgn 사용자 캐릭터명
     * @param characters 캐릭터 목록
     * @return 소유권 여부
     */
    fun verifyCharacterOwnership(
        userIgn: String,
        characters: List<CharacterListResponse.CharacterInfo>,
    ): Boolean {
        val normalizedUserIgn = userIgn.trim()
        val ownsCharacter = characters.any { it.characterName?.trim()?.equals(normalizedUserIgn, ignoreCase = true) == true }

        if (!ownsCharacter) {
            log.warn("Character ownership verification failed: userIgn={}", userIgn)
        }

        return ownsCharacter
    }

    /**
     * API Key를 검증하고 캐릭터 소유권을 확인합니다.
     *
     * <p>#667: Nexon API에서 account_id를 추출하여 계정 식별자로 사용합니다.
     * 동일 Nexon 계정의 다른 API Key라도 동일 account_id를 반환합니다.
     *
     * @param apiKey Nexon API Key
     * @param userIgn 사용자 캐릭터명
     * @return 캐릭터 소유권 검증 결과 (accountId + myOcids)
     * @throws InvalidApiKeyException API Key가 유효하지 않은 경우
     * @throws CharacterNotOwnedException 캐릭터가 사용자 소유가 아닌 경우
     */
    fun validateAndVerifyOwnership(apiKey: String, userIgn: String): CharacterOwnershipValidationResult {
        // 1. API Key 검증 (Nexon API 호출)
        val characterList = validateApiKey(apiKey)

        // 2. 캐릭터 목록 추출
        val characters = characterList.getAllCharacters()

        // 3. 소유권 확인
        val ownsCharacter = verifyCharacterOwnership(userIgn, characters)

        if (!ownsCharacter) {
            throw CharacterNotOwnedException(userIgn)
        }

        // 4. 모든 캐릭터 OCID 수집
        val myOcids = characters
            .mapNotNull { it.ocid?.trim()?.takeIf(String::isNotBlank) }
            .toSet()

        if (myOcids.isEmpty()) {
            log.warn("API key validation failed: no valid OCIDs found for userIgn={}", userIgn)
            throw InvalidApiKeyException("No valid character OCIDs found for API key")
        }

        // 5. #667: Nexon account_id 추출 (동일 계정 = 동일 ID, API Key 무관)
        val accountId = characterList.accountList
            ?.asSequence()
            ?.mapNotNull { it.accountId?.trim()?.takeIf(String::isNotBlank) }
            ?.firstOrNull()
            ?: throw InvalidApiKeyException("Nexon account_id is missing in character/list response")

        log.info("API key validation successful: userIgn={}, ocids={}, accountId={}", userIgn, myOcids.size, accountId)

        return CharacterOwnershipValidationResult(accountId = accountId, myOcids = myOcids)
    }

    /**
     * 캐릭터 소유권 검증 결과
     *
     * @property accountId Nexon 계정 식별자 (동일 계정 = 동일 ID, API Key 무관)
     * @property myOcids 사용자가 소유한 모든 캐릭터 OCID 목록
     */
    data class CharacterOwnershipValidationResult(
        val accountId: String,
        val myOcids: Set<String>,
    ) {
        init {
            require(accountId.isNotBlank()) { "accountId must not be blank" }
            require(myOcids.isNotEmpty()) { "myOcids must not be null or empty" }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApiKeyValidator::class.java)
    }
}
