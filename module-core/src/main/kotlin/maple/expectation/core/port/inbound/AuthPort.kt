package maple.expectation.core.port.inbound

/**
 * 인증 포트 (ADR-005)
 *
 * <h3>역할</h3>
 * <p>인증 관련 유스케이스를 정의하는 인바운드 포트
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>AuthPortAdapter: AuthService에 위임
 * </ul>
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>module-core는 web DTO를 참조하지 않음
 *   <li>Port 전용 Command/Result DTO 사용
 *   <li>Adapter에서 web DTO ↔ core DTO 변환
 * </ul>
 */
interface AuthPort {

    /**
     * 로그인 처리
     *
     * @param command 로그인 요청 (apiKey, userIgn)
     * @return 로그인 결과 (accessToken, expiresIn, role, refreshToken)
     */
    fun login(command: AuthCommand): AuthResult

    /**
     * 로그아웃 처리
     *
     * @param sessionId 세션 ID
     */
    fun logout(sessionId: String)

    /**
     * 토큰 갱신
     *
     * @param refreshTokenId 기존 Refresh Token ID
     * @return 갱신된 토큰 결과
     */
    fun refresh(refreshTokenId: String): TokenResult
}
