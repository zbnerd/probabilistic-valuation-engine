package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.TokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AuthController 단위 테스트
 *
 * <p>AuthController의 DTO 클래스들이 올바르게 구성되어 있는지 확인합니다.
 */
@Tag("unit")
class AuthControllerUnitTest {

  @Test
  @DisplayName("AuthController 클래스 존재 확인")
  void authController_shouldExist() {
    // Given & When
    Class<AuthController> clazz = AuthController.class;

    // Then
    assertThat(clazz).isNotNull();
    assertThat(
            clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class))
        .isTrue();
  }

  @Test
  @DisplayName("LoginRequest DTO 생성 확인")
  void loginRequest_shouldBeCreatable() {
    // Given & When
    LoginRequest request = new LoginRequest("test-api-key", "TestCharacter");

    // Then
    assertThat(request.apiKey()).isEqualTo("test-api-key");
    assertThat(request.userIgn()).isEqualTo("TestCharacter");
  }

  @Test
  @DisplayName("LoginResponse DTO 생성 확인")
  void loginResponse_shouldBeCreatable() {
    // Given & When
    LoginResponse response =
        LoginResponse.of("access-token", 3600L, "USER", "fingerprint", "refresh-token", 86400L);

    // Then
    assertThat(response.getAccessToken()).isEqualTo("access-token");
    assertThat(response.getExpiresIn()).isEqualTo(3600L);
  }

  @Test
  @DisplayName("TokenResponse DTO 생성 확인")
  void tokenResponse_shouldBeCreatable() {
    // Given & When
    TokenResponse response = TokenResponse.of("access-token", 3600L, "refresh-token", 86400L);

    // Then
    assertThat(response.getAccessToken()).isEqualTo("access-token");
    assertThat(response.getAccessExpiresIn()).isEqualTo(3600L);
  }
}
