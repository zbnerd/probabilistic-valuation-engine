package maple.expectation.application.service.shutdown;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ShutdownDataRecoveryService 단위 테스트
 *
 * <p>ShutdownDataRecoveryService 클래스가 올바르게 구성되어 있는지 확인합니다.
 */
@Tag("unit")
class ShutdownDataRecoveryServiceUnitTest {

  @Test
  @DisplayName("ShutdownDataRecoveryService 클래스 존재 확인")
  void shutdownDataRecoveryService_shouldExist() {
    // Given & When
    Class<ShutdownDataRecoveryService> clazz = ShutdownDataRecoveryService.class;

    // Then
    assertThat(clazz).isNotNull();
    assertThat(clazz.isAnnotationPresent(org.springframework.stereotype.Service.class)).isTrue();
  }
}
