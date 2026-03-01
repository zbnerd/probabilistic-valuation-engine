package maple.expectation.core.port.out

/**
 * 토큰 생성 및 검증 포트 (Issue #278)
 *
 * <h3>역할</h3>
 *
 * <p>JWT 토큰 생성과 검증을 위한 인터페이스입니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>JwtTokenProvider: JWT 기반 토큰 생성/검증
 * </ul>
 */
interface TokenPort {

    /**
     * 토큰 생성
     *
     * @param userId 사용자 ID
     * @return 생성된 JWT 토큰
     */
    fun generateToken(userId: Long): String

    /**
     * 토큰 검증
     *
     * @param token JWT 토큰
     * @return 사용자 ID (유효하지 않은 토큰인 경우 null)
     */
    fun validateToken(token: String): Long?
}
