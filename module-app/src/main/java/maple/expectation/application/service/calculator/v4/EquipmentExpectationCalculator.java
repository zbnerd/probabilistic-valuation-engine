package maple.expectation.application.service.calculator.v4;

import java.util.Optional;

/**
 * V4 장비 기대값 계산기 인터페이스 (#240)
 *
 * <h3>성능 최적화 (2026-03-23)</h3>
 *
 * <ul>
 *   <li>BigDecimal → Double로 변경하여 계산 비용 절감
 *   <li>내부 루프에서 Kahan Summation 사용으로 정확도 유지
 *   <li>최종 결과만 BigDecimal로 변환 (경계 계층)
 * </ul>
 *
 * <h3>기존 ExpectationCalculator와의 차이</h3>
 *
 * <ul>
 *   <li>calculateCost() → Double (BigDecimal에서 변경)
 *   <li>getTrials() → Double (정밀 기대값 계산)
 *   <li>새로운 메서드: getDetailedCosts() - 비용 상세 분류
 * </ul>
 *
 * @see maple.expectation.service.v2.calculator.ExpectationCalculator 기존 V2/V3 인터페이스
 */
public interface EquipmentExpectationCalculator {

  /**
   * 최종 소모 비용 합산 (Double)
   *
   * <p>성능 최적화: Double로 계산하여 BigDecimal 오버헤드 제거
   *
   * @return 기대 비용 (메소 단위)
   */
  double calculateCost();

  /**
   * 적용된 강화 경로 문자열 반환
   *
   * @return 강화 경로 (예: "무기 > 블랙큐브(윗잠) > 레드큐브(윗잠) > 에디셔널(아랫잠) > 스타포스")
   */
  String getEnhancePath();

  /**
   * 기대 시도 횟수 (기하분포 기반)
   *
   * @return 기대 시도 횟수 (없으면 Optional.empty())
   */
  Optional<Double> getTrials();

  /**
   * 비용 상세 분류
   *
   * <p>V4 API에서 항목별 비용 분류를 위해 사용
   *
   * @return 비용 상세 (블랙큐브, 레드큐브, 에디셔널, 스타포스 등)
   */
  CostBreakdown getDetailedCosts();

  /**
   * 비용 상세 분류 Record (#240 V4: trials 추가)
   *
   * <p>trials는 기대 시도 횟수로, Double로 변환하여 사용합니다.
   */
  record CostBreakdown(
      double blackCubeCost,
      double redCubeCost,
      double additionalCubeCost,
      double starforceCost,
      double blackCubeTrials, // #240 V4: 블랙큐브 기대 시도 횟수
      double redCubeTrials, // #240 V4: 레드큐브 기대 시도 횟수
      double additionalCubeTrials // #240 V4: 에디셔널큐브 기대 시도 횟수
      ) {
    public static CostBreakdown empty() {
      return new CostBreakdown(
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public double total() {
      return blackCubeCost + redCubeCost + additionalCubeCost + starforceCost;
    }

    public CostBreakdown withBlackCube(double cost) {
      return new CostBreakdown(
          cost,
          redCubeCost,
          additionalCubeCost,
          starforceCost,
          blackCubeTrials,
          redCubeTrials,
          additionalCubeTrials);
    }

    public CostBreakdown withBlackCube(double cost, double trials) {
      return new CostBreakdown(
          cost,
          redCubeCost,
          additionalCubeCost,
          starforceCost,
          trials,
          redCubeTrials,
          additionalCubeTrials);
    }

    public CostBreakdown withRedCube(double cost) {
      return new CostBreakdown(
          blackCubeCost,
          cost,
          additionalCubeCost,
          starforceCost,
          blackCubeTrials,
          redCubeTrials,
          additionalCubeTrials);
    }

    public CostBreakdown withRedCube(double cost, double trials) {
      return new CostBreakdown(
          blackCubeCost,
          cost,
          additionalCubeCost,
          starforceCost,
          blackCubeTrials,
          trials,
          additionalCubeTrials);
    }

    public CostBreakdown withAdditionalCube(double cost) {
      return new CostBreakdown(
          blackCubeCost,
          redCubeCost,
          cost,
          starforceCost,
          blackCubeTrials,
          redCubeTrials,
          additionalCubeTrials);
    }

    public CostBreakdown withAdditionalCube(double cost, double trials) {
      return new CostBreakdown(
          blackCubeCost, redCubeCost, cost, starforceCost, blackCubeTrials, redCubeTrials, trials);
    }

    public CostBreakdown withStarforce(double cost) {
      return new CostBreakdown(
          blackCubeCost,
          redCubeCost,
          additionalCubeCost,
          cost,
          blackCubeTrials,
          redCubeTrials,
          additionalCubeTrials);
    }
  }
}
