package maple.expectation.controller.dto.auth

import jakarta.validation.constraints.NotBlank

/**
 * 로그인 요청 DTO
 *
 * **보안 고려사항:**
 * - toString() 오버라이드: API Key 마스킹 (로그 노출 방지)
 * - 마스킹 형식: 앞 4자리 + **** + 뒤 4자리
 *
 * @param apiKey Nexon API Key
 * @param userIgn 사용자 캐릭터 닉네임 (소유권 검증용)
 */
data class LoginRequest(
    @field:NotBlank(message = "API Key는 필수입니다.")
    val apiKey: String,
    @field:NotBlank(message = "캐릭터 닉네임은 필수입니다.")
    val userIgn: String
) {
    /**
     * API Key 마스킹된 문자열 반환 (로그 보안)
     * TraceAspect 등에서 자동 로깅 시 API Key 노출 방지
     */
    override fun toString(): String {
        return "LoginRequest[apiKey=${maskApiKey(apiKey)}, userIgn=$userIgn]"
    }

    /** API Key 마스킹 (앞 4자리 + **** + 뒤 4자리) */
    private fun maskApiKey(key: String): String {
        if (key.length < 8) {
            return "****"
        }
        return key.substring(0, 4) + "****" + key.substring(key.length - 4)
    }
}
