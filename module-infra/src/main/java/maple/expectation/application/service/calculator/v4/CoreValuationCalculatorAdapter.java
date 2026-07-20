package maple.expectation.application.service.calculator.v4;

import java.util.Optional;
import maple.expectation.core.calculation.ValuationResult;

/** Legacy V4 calculator surface backed by one immutable core valuation result. */
public final class CoreValuationCalculatorAdapter implements EquipmentExpectationCalculator {

  private final ValuationResult result;

  public CoreValuationCalculatorAdapter(ValuationResult result) {
    this.result = result;
  }

  @Override
  public double calculateCost() {
    Double total = result.getCosts().getTotalCost();
    return total == null ? 0.0 : total;
  }

  @Override
  public String getEnhancePath() {
    return result.getEnhancePath();
  }

  @Override
  public Optional<Double> getTrials() {
    if (result.getCosts().getStarforceCost() != null) {
      return Optional.empty();
    }
    Double additionalTrials = result.getTrials().getAdditionalCubeTrials();
    if (additionalTrials != null) {
      return Optional.of(additionalTrials);
    }
    Double blackTrials = result.getTrials().getBlackCubeTrials();
    return Optional.of(blackTrials == null ? 0.0 : blackTrials);
  }

  @Override
  public CostBreakdown getDetailedCosts() {
    return new CostBreakdown(
        valueOrZero(result.getCosts().getBlackCubeCost()),
        0.0,
        valueOrZero(result.getCosts().getAdditionalCubeCost()),
        valueOrZero(result.getCosts().getStarforceCost()),
        roundedTrialsOrZero(result.getTrials().getBlackCubeTrials()),
        0.0,
        roundedTrialsOrZero(result.getTrials().getAdditionalCubeTrials()));
  }

  private static double valueOrZero(Double value) {
    return value == null ? 0.0 : value;
  }

  private static double roundedTrialsOrZero(Double value) {
    return value == null ? 0.0 : (double) Math.round(value);
  }
}
