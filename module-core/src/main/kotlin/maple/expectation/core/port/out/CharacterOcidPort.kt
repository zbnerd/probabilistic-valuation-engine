package maple.expectation.core.port.out

/**
 * Character OCID Resolution Port (ADR-005, ADR-030)
 *
 * <h3>역할</h3>
 * <p>IGN과 OCID 간의 변환 및 사용자가 소유한 모든 캐릭터 OCID 조회를 위한 인터페이스
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>CharacterOcidAdapter: GameCharacterRepository에 위임하여 OCID를 조회
 * </ul>
 *
 * <h3>사용처</h3>
 * <ul>
 *   <li>JwtAuthenticationFilter: JWT 토큰에서 추출한 사용자 정보로 myOcids를 조회하여 Self-Like 방지
 *   <li>OcidResolutionService: 단일 IGN → OCID 변환 (기존 기능 유지)
 * </ul>
 */
interface CharacterOcidPort {

    /**
     * IGN을 OCID로 변환
     *
     * <p>존재하지 않는 캐릭터인 경우 null을 반환합니다.
     *
     * @param userIgn 캐릭터 IGN
     * @return OCID 문자열, 캐릭터가 없으면 null
     */
    fun resolveOcid(userIgn: String): String?

    /**
     * 지정된 IGN 목록에 해당하는 OCID들을 조회합니다.
     *
     * <p>존재하지 않는 IGN은 결과에 포함되지 않습니다.
     *
     * @param userIgns 캐릭터 IGN 목록
     * @return IGN → OCID 매핑 (존재하는 것만)
     */
    fun resolveOcids(userIgns: Set<String>): Map<String, String>

    /**
     * 현재 시스템에 존재하는 모든 캐릭터의 IGN과 OCID를 조회합니다.
     *
     * <p><b>성능 고려사항:</b> 결과 셋이 클 수 있으므로 호출 시점에 주의가 필요합니다.
     *
     * <p><b>제약사항:</b> 현재 구조에서는 `game_character` 테이블이 사용자의 API Key(fingerprint)를
     * 저장하지 않습니다. 따라서 이 메서드는 DB에 존재하는 모든 캐릭터를 반환합니다.
     *
     * <p><b>TODO (P1):</b> 정확한 Self-Like 방지를 위해 `game_character` 테이블에
     * `fingerprint` 컬럼을 추가하여 사용자가 소유한 캐릭터만 정확히 식별할 수 있어야 합니다.
     * 현재는 모든 캐릭터를 조회한 후 애플리케이션 레벨에서 필터링해야 하는 제약이 있습니다.
     *
     * @return 모든 캐릭터의 (IGN → OCID) 매핑
     */
    fun resolveAllOcids(): Map<String, String>
}
