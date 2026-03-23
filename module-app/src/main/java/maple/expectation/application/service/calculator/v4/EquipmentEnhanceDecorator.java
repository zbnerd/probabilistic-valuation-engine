package maple.expectation.application.service.calculator.v4;

import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * V4 장비 강화 데코레이터 추상 클래스 (#240)
 *
 * <h3>Decorator Pattern (GoF)</h3>
 *
 * <p>기존 ExpectationCalculator를 감싸서 추가 강화 비용을 누적합니다.
 *
 * <h3>SOLID - OCP 준수</h3>
 *
 * <p>새로운 강화 타입 추가 시 이 클래스를 상속하여 확장
 *
 * <h3>성능 최적화 (2026-03-23)</h3>
 *
 * <ul>
 *   <li>BigDecimal → Double로 변경하여 계산 비용 절감
 *   <li>모든 반환 타입을 Double로 통일
 * </ul>
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@RequiredArgsConstructor
public abstract class EquipmentEnhanceDecorator implements EquipmentExpectationCalculator {

  protected final EquipmentExpectationCalculator target;

  @Override
  public double calculateCost() {
    return target.calculateCost();
  }

  @Override
  public String getEnhancePath() {
    return target.getEnhancePath();
  }

  @Override
  public Optional<Double> getTrials() {
    return target.getTrials();
  }

  @Override
  public CostBreakdown getDetailedCosts() {
    return target.getDetailedCosts();
  }
}
