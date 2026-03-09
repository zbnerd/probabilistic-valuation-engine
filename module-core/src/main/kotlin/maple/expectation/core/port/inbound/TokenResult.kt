package maple.expectation.core.port.inbound

/**
 * 토큰 갱신 결과 (ADR-005)
 *
 * <p>module-core 전용 DTO로, web 계층의 TokenResponse와 독립적입니다.
 *
 * @param accessToken 새 Access Token
 * @param expiresIn Access Token expiration time (seconds)
 * @param refreshToken 새 Refresh Token ID
 * @param refreshExpiresIn Refresh Token expiration time (seconds)
 */
data class TokenResult(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val refreshExpiresIn: Long,
) {
    companion object {
        /**
         * Factory method for creating TokenResult
         */
        @JvmStatic
        fun of(
            accessToken: String,
            expiresIn: Long,
            refreshToken: String,
            refreshExpiresIn: Long,
        ): TokenResult = TokenResult(
            accessToken = accessToken,
            expiresIn = expiresIn,
            refreshToken = refreshToken,
            refreshExpiresIn = refreshExpiresIn,
        )
    }
}
