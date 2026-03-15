package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.inbound.AuthCommand;
import maple.expectation.core.port.inbound.AuthPort;
import maple.expectation.core.port.inbound.AuthResult;
import maple.expectation.core.port.inbound.TokenResult;
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider;
import org.springframework.stereotype.Component;

/**
 * AuthPort 구현체 (ADR-005)
 *
 * <p>책임: 인증 관련 유스케이스 구현
 *
 * <p>V5 Migration (Issue #589): Redis 기반 세션/리프레시 토큰 저장소 제거 후 JWT-only 방식으로 단순화.
 *
 * <p>현재 상태: Refresh Token 기능 미지원 (Stateless JWT만 사용)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthPortAdapter implements AuthPort {

  private final JwtTokenProvider jwtTokenProvider;
  private static final String DEFAULT_ROLE = "USER";
  private static final long REFRESH_EXPIRES_IN = 604800L; // 7 days (not implemented yet)

  @Override
  public AuthResult login(AuthCommand command) {
    log.info("[AuthPort] Login attempt: userIgn={}", command.getUserIgn());

    // Generate session ID and fingerprint from apiKey
    String sessionId = generateSessionId(command.getApiKey());
    String fingerprint = generateFingerprint(command.getApiKey());

    // Determine role (check if admin)
    String role = determineRole(fingerprint);

    // Generate JWT token
    String accessToken = jwtTokenProvider.generateToken(sessionId, fingerprint, role);
    long expiresIn = jwtTokenProvider.getExpirationSeconds();

    log.info("[AuthPort] Login successful: sessionId={}, role={}", sessionId, role);

    return AuthResult.of(
        accessToken,
        expiresIn,
        role,
        fingerprint,
        "refresh-" + sessionId, // Placeholder refresh token
        REFRESH_EXPIRES_IN);
  }

  @Override
  public void logout(String sessionId) {
    log.info("[AuthPort] Logout: sessionId={}", sessionId);
    // Stateless JWT - no server-side session to invalidate
    // Token will expire naturally
  }

  @Override
  public TokenResult refresh(String refreshTokenId) {
    log.warn("[AuthPort] Token refresh not implemented yet. refreshTokenId={}", refreshTokenId);
    throw new UnsupportedOperationException(
        "Token refresh is not implemented in this version. Please login again.");
  }

  private String generateSessionId(String apiKey) {
    return "session-" + Math.abs(apiKey.hashCode()) + "-" + System.currentTimeMillis();
  }

  private String generateFingerprint(String apiKey) {
    // Simple fingerprint generation from API key
    return "fp-" + Integer.toHexString(apiKey.hashCode());
  }

  private String determineRole(String fingerprint) {
    // TODO: Check against admin allowlist
    return DEFAULT_ROLE;
  }
}
