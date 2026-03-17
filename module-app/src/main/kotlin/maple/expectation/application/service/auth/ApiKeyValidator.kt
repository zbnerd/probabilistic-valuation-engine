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
        val ownsCharacter = characters.any { it.characterName.equals(userIgn, ignoreCase = true) }

        if (!ownsCharacter) {
            log.warn("Character ownership verification failed: userIgn={}", userIgn)
        }

        return ownsCharacter
    }

    /**
     * API Key를 검증하고 캐릭터 소유권을 확인합니다.
     *
     * @param apiKey Nexon API Key
     * @param userIgn 사용자 캐릭터명
     * @return 캐릭터 소유권 검증 결과
     * @throws InvalidApiKeyException API Key가 유효하지 않은 경우
     * @throws CharacterNotOwnedException 캐릭터가 사용자 소유가 아닌 경우
     */
    fun validateAndVerifyOwnership(apiKey: String, userIgn: String): CharacterOwnershipValidationResult {
        // 1. API Key 검증
        val characterList = validateApiKey(apiKey)

        // 2. 캐릭터 목록 추출
        val characters = characterList.getAllCharacters()

        // 3. 소유권 확인
        val ownsCharacter = verifyCharacterOwnership(userIgn, characters)

        if (!ownsCharacter) {
            throw CharacterNotOwnedException(userIgn)
        }

        // 4. 모든 캐릭터 OCID 수집
        val myOcids = characters.mapNotNull { it.ocid }.toSet()

        log.info("API key validation successful: userIgn={}, ocids={}", userIgn, myOcids.size)

        return CharacterOwnershipValidationResult(myOcids)
    }

    /**
     * 캐릭터 소유권 검증 결과
     *
     * @property myOcids 사용자가 소유한 모든 캐릭터 OCID 목록
     */
    data class CharacterOwnershipValidationResult(val myOcids: Set<String>) {
        init {
            require(myOcids.isNotEmpty()) { "myOcids must not be null or empty" }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApiKeyValidator::class.java)
    }
}
