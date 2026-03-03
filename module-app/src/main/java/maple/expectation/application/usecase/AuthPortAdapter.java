package maple.expectation.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.auth.AuthService;
import maple.expectation.core.port.inbound.AuthCommand;
import maple.expectation.core.port.inbound.AuthPort;
import maple.expectation.core.port.inbound.AuthResult;
import maple.expectation.core.port.inbound.TokenResult;
import maple.expectation.web.dto.LoginRequest;
import maple.expectation.web.dto.LoginResponse;
import maple.expectation.web.dto.TokenResponse;
import org.springframework.stereotype.Component;

/**
 * AuthPort 구현체 (ADR-005)
 *
 * <p>책임: AuthService에 위임(delegate) 및 DTO 변환
 *
 * <p>변환 책임:
 *
 * <ul>
 *   <li>web DTO (LoginRequest) → core DTO (AuthCommand)
 *   <li>core DTO (AuthResult) → web DTO (LoginResponse)
 * </ul>
 *
 * <p>위임 이유:
 *
 * <ul>
 *   <li>순환 의존성 해결: module-web → module-app → module-core
 *   <li>기존 Service 로직 재사용
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthPortAdapter implements AuthPort {

  private final AuthService authService;

  @Override
  public AuthResult login(AuthCommand command) {
    // Core DTO → Web DTO 변환
    LoginRequest request = new LoginRequest(command.getApiKey(), command.getUserIgn());

    // AuthService 호출
    LoginResponse response = authService.login(request);

    // Web DTO → Core DTO 변환
    return AuthResult.of(
        response.getAccessToken(),
        response.getExpiresIn(),
        response.getRole(),
        response.getFingerprint(),
        response.getRefreshToken(),
        response.getRefreshExpiresIn());
  }

  @Override
  public void logout(String sessionId) {
    authService.logout(sessionId);
  }

  @Override
  public TokenResult refresh(String refreshTokenId) {
    // AuthService 호출
    TokenResponse response = authService.refresh(refreshTokenId);

    // Web DTO → Core DTO 변환
    return TokenResult.of(
        response.getAccessToken(),
        response.getAccessExpiresIn(),
        response.getRefreshToken(),
        response.getRefreshExpiresIn());
  }
}
