package maple.expectation.controller.dto.auth;

/**
 * 로그인 응답 DTO
 *
 * @param accessToken JWT Access Token
 * @param expiresIn Access Token 만료 시간 (초)
 * @param role 사용자 역할 (USER, ADMIN)
 * @param fingerprint API Key의 HMAC-SHA256 해시
 * @param refreshToken Refresh Token ID
 * @param refreshExpiresIn Refresh Token 만료 시간 (초)
 */
public record LoginResponse(
    String accessToken,
    Long expiresIn,
    String role,
    String fingerprint,
    String refreshToken,
    Long refreshExpiresIn) {

  /**
   * 정적 팩토리 메서드 (LoginResponse 생성)
   *
   * @param accessToken JWT Access Token
   * @param expiresIn Access Token 만료 시간 (초)
   * @param role 사용자 역할 (USER, ADMIN)
   * @param fingerprint API Key의 HMAC-SHA256 해시
   * @param refreshToken Refresh Token ID
   * @param refreshExpiresIn Refresh Token 만료 시간 (초)
   * @return LoginResponse 인스턴스
   */
  public static LoginResponse of(
      String accessToken,
      Long expiresIn,
      String role,
      String fingerprint,
      String refreshToken,
      Long refreshExpiresIn) {
    return new LoginResponse(
        accessToken, expiresIn, role, fingerprint, refreshToken, refreshExpiresIn);
  }
}
