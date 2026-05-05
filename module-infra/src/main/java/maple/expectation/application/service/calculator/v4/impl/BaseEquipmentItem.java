package maple.expectation.application.service.calculator.v4.impl;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator;

/**
 * V4 기본 장비 아이템 (Decorator Pattern - Concrete Component) (#240)
 *
 * <h3>역할</h3>
 *
 * <p>Decorator 체인의 시작점. 기본 아이템 자체의 비용은 0입니다.
 *
 * <h3>성능 최적화 (2026-03-23)</h3>
 *
 * <ul>
 *   <li>BigDecimal → Double로 변경
 *   <li>모든 반환 타입을 Double로 통일
 * </ul>
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@RequiredArgsConstructor
public class BaseEquipmentItem implements EquipmentExpectationCalculator {

  private final String itemName;
  private final int itemLevel;
  private final int currentStar;

  @Override
  public double calculateCost() {
    return 0.0; // 기본 아이템 자체의 비용은 0
  }

  @Override
  public String getEnhancePath() {
    return itemName;
  }

  @Override
  public Optional<Double> getTrials() {
    return Optional.of(0.0);
  }

  @Override
  public CostBreakdown getDetailedCosts() {
    return CostBreakdown.empty();
  }

  public int getItemLevel() {
    return itemLevel;
  }

  public int getCurrentStar() {
    return currentStar;
  }
}
