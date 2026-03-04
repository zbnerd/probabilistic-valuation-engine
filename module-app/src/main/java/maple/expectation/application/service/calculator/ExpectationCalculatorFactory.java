package maple.expectation.application.service.calculator;

import lombok.RequiredArgsConstructor;
import maple.expectation.application.service.calculator.impl.BaseItem;
import maple.expectation.application.service.calculator.impl.BlackCubeDecorator;
import maple.expectation.application.service.cube.CubeTrialsProvider;
import maple.expectation.application.service.cube.policy.CubeCostPolicy;
import maple.expectation.web.dto.CubeCalculationInput;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExpectationCalculatorFactory {

  private final CubeTrialsProvider trialsProvider;
  private final CubeCostPolicy costPolicy;

  public ExpectationCalculator createBlackCubeCalculator(CubeCalculationInput input) {
    ExpectationCalculator calculator = new BaseItem(input.getItemName());
    // 💡 향후 레드큐브나 에디셔널 장식자가 추가되어도 여기서만 로직을 변경하면 됩니다.
    return new BlackCubeDecorator(calculator, trialsProvider, costPolicy, input);
  }
}
