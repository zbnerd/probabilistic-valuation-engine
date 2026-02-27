package maple.expectation.service.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.calculator.PotentialCalculator;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.support.TestLogicExecutors;
import maple.expectation.testfixtures.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Slf4j
@ExtendWith(MockitoExtension.class)
class PotentialCalculatorTest {

  private LogicExecutor executor;

  @Mock private StatParser statParser;

  private PotentialCalculator calculator;

  @BeforeEach
  void setUp() {
    executor = TestLogicExecutors.passThrough();
    calculator = new PotentialCalculator(statParser, executor);

    when(statParser.parseNum(anyString()))
        .thenAnswer(
            inv -> {
              String arg = inv.getArgument(0);
              if (arg.contains("12")) return 12;
              if (arg.contains("9")) return 9;
              if (arg.contains("6")) return 6;
              return 0;
            });
  }

  @Test
  @DisplayName("잠재능력 3줄 합산 테스트 (올스탯 포함 계산)")
  void calculate_manual_test() {
    // given - Fixtures 사용 (positional arguments for Java)
    EquipmentResponse.ItemEquipment item =
        Fixtures.itemEquipment(
            null,
            null,
            "TestEquipment",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "STR +12%",
            "STR +9%",
            "올스탯 +6%");

    // when
    Map<StatType, Integer> stats = calculator.calculateMainPotential(item);

    // then
    assertThat(calculator.getEffectiveStat(stats, StatType.STR)).isEqualTo(27);
    assertThat(calculator.getEffectiveStat(stats, StatType.LUK)).isEqualTo(6);
    assertThat(stats.get(StatType.ALL_STAT)).isEqualTo(6);

    log.info("STR 최종: {}", calculator.getEffectiveStat(stats, StatType.STR));
    log.info("LUK 최종: {}", calculator.getEffectiveStat(stats, StatType.LUK));
  }
}
