package maple.expectation.infrastructure.security.jwt

import java.time.Instant

/**
 * JWT 페이로드 데이터를 담는 불변 Record
 *
 * <p>보안 주의사항: apiKey는 절대 포함하지 않습니다!
 *
 * @property sessionId 세션 식별자 (UUID)
 * @property fingerprint API Key의 HMAC-SHA256 해시
 * @property role 사용자 권한 (USER 또는 ADMIN)
 * @property issuedAt 토큰 발급 시간
 * @property expiration 토큰 만료 시간
 */
data class JwtPayload(
    val sessionId: String,
    val fingerprint: String,
    val role: String,
    val issuedAt: Instant,
    val expiration: Instant,
) {
    /**
     * 토큰이 만료되었는지 확인합니다.
     *
     * @return 만료 여부
     */
    fun isExpired(): Boolean = Instant.now().isAfter(expiration)

    companion object {
        /**
         * 새 토큰 생성용 팩토리 메서드
         *
         * @param sessionId 세션 ID
         * @param fingerprint fingerprint
         * @param role 권한
         * @param ttlSeconds 유효 시간 (초)
         * @return JwtPayload
         */
        fun of(sessionId: String, fingerprint: String, role: String, ttlSeconds: Long): JwtPayload {
            val now = Instant.now()
            return JwtPayload(sessionId, fingerprint, role, now, now.plusSeconds(ttlSeconds))
        }
    }
}
