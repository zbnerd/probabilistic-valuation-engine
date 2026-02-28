package maple.expectation.core.port.inbound

/**
 * 인증 요청 Command (ADR-005)
 *
 * <p>module-core 전용 DTO로, web 계층의 LoginRequest와 독립적입니다.
 *
 * @param apiKey Nexon API Key
 * @param userIgn 사용자 캐릭터 닉네임 (소유권 검증용)
 */
data class AuthCommand(
    val apiKey: String,
    val userIgn: String
) {
    init {
        require(apiKey.isNotBlank()) { "API Key는 필수입니다." }
        require(userIgn.isNotBlank()) { "캐릭터 닉네임은 필수입니다." }
    }

    companion object {
        /**
         * Factory method for creating AuthCommand
         */
        @JvmStatic
        fun of(apiKey: String, userIgn: String): AuthCommand = AuthCommand(apiKey, userIgn)
    }
}
