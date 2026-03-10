package maple.expectation.core.port.inbound

/**
 * 인증 결과 (ADR-005)
 *
 * <p>module-core 전용 DTO로, web 계층의 LoginResponse와 독립적입니다.
 *
 * @param accessToken JWT access token
 * @param expiresIn Access Token expiration time (seconds)
 * @param role 사용자 역할 (USER/ADMIN)
 * @param fingerprint 계정 식별자 (Admin 등록용)
 * @param refreshToken Refresh Token ID
 * @param refreshExpiresIn Refresh Token expiration time (seconds)
 */
data class AuthResult(
    val accessToken: String,
    val expiresIn: Long,
    val role: String,
    val fingerprint: String,
    val refreshToken: String,
    val refreshExpiresIn: Long,
) {
    companion object {
        /**
         * Factory method for creating AuthResult
         */
        @JvmStatic
        fun of(
            accessToken: String,
            expiresIn: Long,
            role: String,
            fingerprint: String,
            refreshToken: String,
            refreshExpiresIn: Long,
        ): AuthResult = AuthResult(
            accessToken = accessToken,
            expiresIn = expiresIn,
            role = role,
            fingerprint = fingerprint,
            refreshToken = refreshToken,
            refreshExpiresIn = refreshExpiresIn,
        )
    }
}
