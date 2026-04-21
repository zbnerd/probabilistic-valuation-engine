package maple.expectation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.core.dto.cube.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.support.EnableTimeLogging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Slf4j
@EnableTimeLogging
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class CubeServiceTest {

  @Autowired private CubeTrialsProvider cubeTrialsProvider; // 인터페이스로 주입 (Proxy가 주입됨)

  @Test
  @DisplayName("실전 테스트: 200제 모자, STR 3줄(12%, 9%, 9%) 띄우는 기대 횟수 계산")
  void calculate_real_trials_test() {
    // 1. given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("하이네스 워리어헬름")
            .part("모자")
            .level(200)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            .build();

    // 2. when: 이제 '비용'이 아닌 '횟수(Trials)'를 직접 계산합니다.
    long trials = cubeTrialsProvider.calculateExpectedTrials(input, CubeType.BLACK).longValue();

    // 3. then
    assertThat(trials).isGreaterThan(0);

    log.info("=============================================");
    log.info("아이템: {}", input.getItemName());
    log.info("옵션: {}", input.getOptions());
    log.info("---------------------------------------------");
    log.info("🎲 기대 재설정 횟수: 약 {}회", String.format("%,d", trials));
    log.info("=============================================");
  }

  @Test
  @DisplayName("쿨감 4초(-2초, -2초, 아무거나) 띄우는 기대 횟수 계산")
  void calculate_cooldown_trials_test() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("에테르넬 나이트헬름")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(Arrays.asList(null, "스킬 재사용 대기시간 -2초", "스킬 재사용 대기시간 -2초"))
            .build();

    // when
    long trials = cubeTrialsProvider.calculateExpectedTrials(input, CubeType.BLACK).longValue();

    // then
    assertThat(trials).isGreaterThan(0);

    log.info("=============================================");
    log.info("목표: 쿨감 4초 (나머지 한 줄 무관)");
    log.info("🎲 기대 재설정 횟수: 약 {}회", String.format("%,d", trials));
    log.info("=============================================");
  }
}
