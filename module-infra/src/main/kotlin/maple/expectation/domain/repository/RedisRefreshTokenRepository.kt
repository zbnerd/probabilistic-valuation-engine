package maple.expectation.domain.repository

import maple.expectation.core.domain.auth.RefreshToken

/**
 * Redis 기반 Refresh Token 저장소 인터페이스
 *
 * <p>저장 구조:
 *
 * <ul>
 *   <li>Token: refresh:{refreshTokenId} → JSON (String)
 *   <li>Family Index: refresh:family:{familyId} → Set&lt;refreshTokenId&gt;
 *   <li>Session Index: refresh:session:{sessionId} → Set&lt;refreshTokenId&gt;
 * </ul>
 *
 * <p>TTL 정책:
 *
 * <ul>
 *   <li>Token TTL: 7일 (auth.refresh-token.expiration)
 *   <li>Family/Session Index TTL: 7일 (토큰과 동일)
 * </ul>
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@code maple.expectation.repository.v2.RedisRefreshTokenRepositoryImpl} - Redisson 기반 구현
 * </ul>
 */
interface RedisRefreshTokenRepository {

    /**
     * Refresh Token 저장
     *
     * @param token 저장할 Refresh Token
     */
    fun save(token: RefreshToken)

    /**
     * Refresh Token 조회
     *
     * @param refreshTokenId Refresh Token ID
     * @return RefreshToken (null if not found)
     */
    fun findById(refreshTokenId: String): RefreshToken?

    /**
     * Refresh Token 사용 처리 (Token Rotation)
     *
     * <p>기존 토큰의 used 필드를 true로 설정하여 재사용 감지 가능하게 함
     *
     * @param refreshTokenId Refresh Token ID
     */
    fun markAsUsed(refreshTokenId: String)

    /**
     * Atomic Check-and-Mark: 토큰 사용 상태 확인 후 마크 (P1 Race Condition Fix)
     *
     * <p>Redis Lua script로 원자적으로 수행하여 TOCTOU 취약점 방지:
     *
     * <ul>
     *   <li>토큰이 존재하지 않으면 null 반환
     *   <li>이미 used=true이면 null 반환 (재사용 감지)
     *   <li>used=false이면 used=true로 변경 후 토큰 반환
     * </ul>
     *
     * @param refreshTokenId Refresh Token ID
     * @return 마크된 RefreshToken (이미 사용되었거나 존재하지 않으면 null)
     */
    fun checkAndMarkAsUsed(refreshTokenId: String): RefreshToken?

    /**
     * Family 전체 무효화 (탈취 감지 시)
     *
     * @param familyId Token Family ID
     */
    fun deleteByFamilyId(familyId: String)

    /**
     * 세션의 모든 Refresh Token 삭제 (로그아웃 시)
     *
     * @param sessionId 세션 ID
     */
    fun deleteBySessionId(sessionId: String)

    /**
     * 단일 Refresh Token 삭제
     *
     * @param refreshTokenId Refresh Token ID
     */
    fun deleteById(refreshTokenId: String)
}
