package maple.expectation.controller.dto.auth;

/**
 * Token Refresh 응답 DTO (Issue #279)
 *
 * @param accessToken 새 JWT Access Token
 * @param accessExpiresIn Access Token 만료 시간 (초)
 * @param refreshToken 새 Refresh Token ID
 * @param refreshExpiresIn Refresh Token 만료 시간 (초)
 */
public record TokenResponse(
    String accessToken, Long accessExpiresIn, String refreshToken, Long refreshExpiresIn) {

  /**
   * 정적 팩토리 메서드 (TokenResponse 생성)
   *
   * @param accessToken 새 JWT Access Token
   * @param accessExpiresIn Access Token 만료 시간 (초)
   * @param refreshToken 새 Refresh Token ID
   * @param refreshExpiresIn Refresh Token 만료 시간 (초)
   * @return TokenResponse 인스턴스
   */
  public static TokenResponse of(
      String accessToken, Long accessExpiresIn, String refreshToken, Long refreshExpiresIn) {
    return new TokenResponse(accessToken, accessExpiresIn, refreshToken, refreshExpiresIn);
  }
}
