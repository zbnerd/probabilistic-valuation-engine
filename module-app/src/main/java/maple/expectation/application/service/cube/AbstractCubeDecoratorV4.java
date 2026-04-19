package maple.expectation.application.service.cube;

import java.util.Optional;
import maple.expectation.application.service.calculator.v4.EquipmentEnhanceDecorator;
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator.CostBreakdown;
import maple.expectation.application.service.cube.policy.CubeCostPolicy;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.web.dto.CubeCalculationInput;

/**
 * V4-specific abstract cube decorator using Double type for performance.
 *
 * <p>Extends AbstractCubeDecorator with V4-specific implementations:
 *
 * <ul>
 *   <li>Type parameter: Double (performance optimization)
 *   <li>Rounds trials to integer (HALF_UP) before cost calculation
 *   <li>Extends EquipmentEnhanceDecorator for V4 calculator chain
 *   <li>Supports CostBreakdown for detailed cost tracking
 * </ul>
 *
 * <h3>V4 Improvements over V2</h3>
 *
 * <ul>
 *   <li>Performance: Double prevents BigDecimal object allocation overhead
 *   <li>Rounding: Trials rounded to integer before multiplication
 *   <li>Detailed breakdown: Separate tracking of cube costs and trials
 * </ul>
 */
public abstract class AbstractCubeDecoratorV4 extends EquipmentEnhanceDecorator {

  private static final int PRECISION_SCALE = 2;

  private final AbstractCubeDecorator<Double, EquipmentExpectationCalculator> delegate;

  /**
   * Constructor that initializes both the decorator chain and the generic delegate.
   *
   * @param target The wrapped calculator (previous enhancement stage)
   * @param trialsProvider Provider for calculating expected trials
   * @param costPolicy Policy for cube cost calculation
   * @param input Input parameters for cube calculation
   */
  protected AbstractCubeDecoratorV4(
      EquipmentExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target);

    // Create delegate with V4-specific implementations
    this.delegate =
        new AbstractCubeDecorator<Double, EquipmentExpectationCalculator>(
            target, trialsProvider, costPolicy, input) {

          @Override
          protected CubeType getCubeType() {
            return AbstractCubeDecoratorV4.this.getCubeType();
          }

          @Override
          protected Optional<Double> getTrialsOptional() {
            return AbstractCubeDecoratorV4.this.getTrials();
          }

          @Override
          protected Double getCostPerTrial() {
            return (double)
                costPolicy.getCubeCost(getCubeType(), input.getLevel(), input.getGrade());
          }

          @Override
          protected Double calculateTotalCost() {
            return AbstractCubeDecoratorV4.this.calculateCost();
          }

          @Override
          protected String getBaseEnhancePath() {
            return AbstractCubeDecoratorV4.this.getBaseEnhancePath();
          }

          @Override
          protected String getCubePathSuffix() {
            return AbstractCubeDecoratorV4.this.getCubePathSuffix();
          }

          @Override
          protected Double convertFromDouble(Double value) {
            return value;
          }

          @Override
          protected Double convertFromLong(long value) {
            return (double) value;
          }

          @Override
          protected Double getZero() {
            return 0.0;
          }

          @Override
          protected Double add(Double a, Double b) {
            return a + b;
          }

          @Override
          protected Double multiply(Double a, Double b) {
            return a * b;
          }

          @Override
          protected boolean shouldRoundTrials() {
            return true; // V4 always rounds trials
          }
        };
  }

  /**
   * Get the cube type for this decorator (subclass must implement).
   *
   * @return CubeType (BLACK, RED, or ADDITIONAL)
   */
  protected abstract CubeType getCubeType();

  /**
   * Get cube-specific path suffix (subclass must implement).
   *
   * @return Path suffix (e.g., " > 블랙큐브(윗잠)")
   */
  protected abstract String getCubePathSuffix();

  /**
   * Calculate expected trials using delegate.
   *
   * @return Expected number of trials (Double)
   */
  public double calculateTrials() {
    return delegate.calculateTrials();
  }

  /**
   * Get trials as Optional.
   *
   * @return Optional containing trials
   */
  @Override
  public Optional<Double> getTrials() {
    return Optional.of(delegate.calculateTrials());
  }

  /**
   * Calculate total cost: previous cost + (rounded trials × cost per trial).
   *
   * <p>V4 improvement: Trials are rounded to integer before cost calculation.
   *
   * @return Total cost (Double)
   */
  @Override
  public double calculateCost() {
    // 1. Previous stage cumulative cost
    double previousCost = super.calculateCost();

    // 2. Expected trials for cube
    double expectedTrials = delegate.calculateTrials();

    // 3. Round trials to integer (V4 improvement)
    long roundedTrials = Math.round(expectedTrials);

    // 4. Cost per trial from policy
    double costPerTrial = delegate.getLongCostPerTrial();

    // 5. Total cost = previous + (roundedTrials × costPerTrial)
    double cubeCost = roundedTrials * costPerTrial;

    return previousCost + cubeCost;
  }

  /**
   * Get detailed cost breakdown with cube-specific costs.
   *
   * <p>Subclasses should override this to add cube-specific cost breakdown.
   *
   * @return CostBreakdown with cube costs added
   */
  @Override
  public CostBreakdown getDetailedCosts() {
    CostBreakdown base = super.getDetailedCosts();

    // Calculate trials and cost
    double expectedTrials = delegate.calculateTrials();
    long roundedTrials = Math.round(expectedTrials);
    double costPerTrial = delegate.getLongCostPerTrial();
    double cubeCost = roundedTrials * costPerTrial;

    // Delegate to subclass for specific CostBreakdown method
    return updateCostBreakdown(base, cubeCost, roundedTrials);
  }

  /**
   * Update CostBreakdown with cube-specific costs (Template Method hook).
   *
   * <p>Subclasses must implement this to call the appropriate CostBreakdown method:
   *
   * <ul>
   *   <li>Black Cube: base.withBlackCube(cost, trials)
   *   <li>Red Cube: base.withRedCube(cost, trials)
   *   <li>Additional Cube: base.withAdditionalCube(cost, trials)
   * </ul>
   *
   * @param base Base CostBreakdown from previous stage
   * @param cubeCost Cost for this cube
   * @param trials Rounded trials for this cube
   * @return Updated CostBreakdown
   */
  protected abstract CostBreakdown updateCostBreakdown(
      CostBreakdown base, double cubeCost, double trials);

  /**
   * Get base enhance path from target.
   *
   * @return Base enhance path
   */
  protected String getBaseEnhancePath() {
    return super.getEnhancePath();
  }

  /**
   * Get complete enhance path with cube suffix.
   *
   * @return Complete enhance path string
   */
  @Override
  public String getEnhancePath() {
    return delegate.getEnhancePath();
  }

  /**
   * Get precision scale for operations.
   *
   * @return Scale value (2)
   */
  protected int getPrecisionScale() {
    return PRECISION_SCALE;
  }
}
