package maple.expectation.core.port.out

import java.util.Optional
import maple.expectation.core.domain.model.character.GameCharacter

/**
 * 게임 캐릭터 포트 (ADR-005)
 *
 * <h3>역할</h3>
 * <p>캐릭터 조회, 생성, 저장을 위한 인터페이스
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>GameCharacterPortAdapter: GameCharacterService에 위임
 * </ul>
 */
interface GameCharacterPort {

    /**
     * 캐릭터 존재하지 않음 확인 (Negative Cache)
     *
     * @param userIgn 캐릭터 IGN
     * @return 존재하지 않으면 true
     */
    fun isNonExistent(userIgn: String): Boolean

    /**
     * 캐릭터 조회 (존재 시)
     *
     * @param userIgn 캐릭터 IGN
     * @return 캐릭터 (없으면 empty)
     */
    fun getCharacterIfExist(userIgn: String): Optional<GameCharacter>

    /**
     * 캐릭터 생성
     *
     * @param userIgn 캐릭터 IGN
     * @return 생성된 캐릭터
     */
    fun createNewCharacter(userIgn: String): GameCharacter

    /**
     * 캐릭터 저장
     *
     * @param character 캐릭터 엔티티
     * @return 저장된 캐릭터 IGN
     */
    fun saveCharacter(character: GameCharacter): String

    /**
     * 캐릭터 조회 (없으면 예외)
     *
     * @param userIgn 캐릭터 IGN
     * @return 캐릭터
     * @throws maple.expectation.error.exception.CharacterNotFoundException 캐릭터가 없을 때
     */
    fun getCharacterOrThrow(userIgn: String): GameCharacter

    /**
     * 캐릭터 기본 정보 보강
     *
     * @param character 캐릭터 엔티티
     * @return 기본 정보가 보강된 캐릭터
     */
    fun enrichCharacterBasicInfo(character: GameCharacter): GameCharacter

    /**
     * 좋아요 버퍼 동기화용 조회 (Lock)
     *
     * @param userIgn 캐릭터 IGN
     * @return 캐릭터
     */
    fun getCharacterForUpdate(userIgn: String): GameCharacter
}
