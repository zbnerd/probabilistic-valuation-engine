package maple.expectation.core.domain.model.security

/**
 * 인증된 사용자 정보 DTO
 *
 * <p>SecurityContext에 저장되어 컨트롤러에서 접근 가능
 *
 * @property sessionId 세션 ID
 * @property fingerprint Nexon account_id (#667: 동일 계정 = 동일 ID, API Key 무관)
 * @property userIgn 로그인 캐릭터명
 * @property accountId Nexon account_id (좋아요 중복 판별 키)
 * @property apiKey Nexon API Key (서비스 레이어에서만 사용)
 * @property myOcids 사용자가 소유한 캐릭터 OCID 목록
 * @property role 권한 (USER 또는 ADMIN)
 */
data class AuthenticatedUser(
    val sessionId: String,
    val fingerprint: String,
    val userIgn: String,
    val accountId: String,
    val apiKey: String,
    val myOcids: Set<String>,
    val role: String,
) {
    /**
     * 주어진 OCID가 이 사용자의 캐릭터인지 확인합니다.
     *
     * @param ocid 확인할 OCID
     * @return 본인 캐릭터 여부
     */
    fun isMyCharacter(ocid: String?): Boolean = myOcids.contains(ocid)

    /** ADMIN 권한인지 확인합니다. */
    fun isAdmin(): Boolean = "ADMIN" == role

    /**
     * API Key 마스킹된 문자열 반환
     *
     * <p><b>CLAUDE.md 섹션 19 준수:</b> AOP 로깅 시 API Key 평문 노출 방지
     *
     * <p><b>Purple Agent P1 FIX:</b> Record 기본 toString()은 모든 필드를 노출하므로 오버라이드 필수
     */
    override fun toString(): String = "AuthenticatedUser[" +
        "sessionId=$sessionId, " +
        "fingerprint=$fingerprint, " +
        "userIgn=$userIgn, " +
        "accountId=$accountId, " +
        "apiKey=${maskApiKey(apiKey)}, " +
        "myOcids=$myOcids, " +
        "role=$role]"

    private fun maskApiKey(key: String?): String = if (key == null || key.length < 8) {
        "****"
    } else {
        key.substring(0, 4) + "****" + key.substring(key.length - 4)
    }
}
