package maple.expectation.application.service.calculator.v4;

import java.util.List;
import lombok.RequiredArgsConstructor;
import maple.expectation.core.calculation.ComponentCosts;
import maple.expectation.core.calculation.ComponentTrials;
import maple.expectation.core.calculation.ValuationInput;
import maple.expectation.core.calculation.ValuationKernel;
import maple.expectation.core.calculation.ValuationResult;
import maple.expectation.core.calculation.cube.DpInference;
import maple.expectation.core.calculation.cube.DpModeInferrer;
import maple.expectation.core.calculation.probability.ProbabilityTableSnapshot;
import maple.expectation.core.dto.cube.CubeCalculationInput;
import maple.expectation.core.dto.v4.EquipmentCalculationInput;
import org.springframework.stereotype.Component;

/**
 * V4 장비 기대값 계산기 팩토리 (#240)
 *
 * <p>Preserves the legacy public factory and calculator interface while delegating every entry
 * point to the immutable core valuation kernel.
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@Component
@RequiredArgsConstructor
public class EquipmentExpectationCalculatorFactory {

  private final ValuationKernel kernel;
  private final ProbabilityTableSnapshot table;
  private final DpModeInferrer modeInferrer = new DpModeInferrer();

  /**
   * 전체 강화 계산기 생성 (블랙큐브 + 에디셔널 + 스타포스)
   *
   * @param input 장비 계산 입력
   * @return 전체 강화 계산기
   */
  public EquipmentExpectationCalculator createFullCalculator(EquipmentCalculationInput input) {
    return calculate(
        new ValuationInput(
            input.getItemName(),
            input.getItemPart(),
            input.getItemEquipmentPart(),
            input.getItemLevel(),
            input.getCurrentStar(),
            input.getTargetStar(),
            input.isNoljang(),
            input.getPotentialGrade(),
            immutableOptions(input.getPotentialOptions()),
            input.getAdditionalPotentialGrade(),
            immutableOptions(input.getAdditionalPotentialOptions())));
  }

  /**
   * 윗잠재(블랙큐브)만 계산하는 계산기 생성
   *
   * @param input 큐브 계산 입력
   * @return 블랙큐브 계산기
   */
  public EquipmentExpectationCalculator createBlackCubeCalculator(CubeCalculationInput input) {
    return calculate(
        new ValuationInput(
            valueOrEmpty(input.getItemName()),
            valueOrEmpty(input.getPart()),
            valueOrEmpty(input.getItemEquipmentPart()),
            input.getLevel(),
            0,
            0,
            false,
            input.getGrade(),
            immutableOptions(input.getOptions()),
            null,
            List.of()));
  }

  /**
   * 아랫잠재(에디셔널큐브)만 계산하는 계산기 생성
   *
   * @param input 큐브 계산 입력
   * @return 에디셔널큐브 계산기
   */
  public EquipmentExpectationCalculator createAdditionalCubeCalculator(CubeCalculationInput input) {
    return calculate(
        new ValuationInput(
            valueOrEmpty(input.getItemName()),
            valueOrEmpty(input.getPart()),
            valueOrEmpty(input.getItemEquipmentPart()),
            input.getLevel(),
            0,
            0,
            false,
            null,
            List.of(),
            input.getGrade(),
            immutableOptions(input.getOptions())));
  }

  /**
   * 스타포스만 계산하는 계산기 생성
   *
   * @param itemName 아이템 이름
   * @param itemLevel 아이템 레벨
   * @param currentStar 현재 스타포스
   * @param targetStar 목표 스타포스
   * @return 스타포스 계산기
   */
  public EquipmentExpectationCalculator createStarforceCalculator(
      String itemName, int itemLevel, int currentStar, int targetStar) {
    return calculate(
        new ValuationInput(
            itemName,
            "",
            "",
            itemLevel,
            currentStar,
            targetStar,
            false,
            null,
            List.of(),
            null,
            List.of()));
  }

  private EquipmentExpectationCalculator calculate(ValuationInput input) {
    ValuationResult result = kernel.calculate(input, table);
    return new CoreValuationCalculatorAdapter(preserveLegacyPermutationFallback(input, result));
  }

  /**
   * The retired V1 permutation facade queried repository slot zero and therefore exposed zero
   * cost/trials for inferred permutation mode. Preserve that frozen public behavior here while the
   * standalone core kernel keeps its slot-specific permutation result.
   */
  private ValuationResult preserveLegacyPermutationFallback(
      ValuationInput input, ValuationResult result) {
    boolean suppressBlack =
        hasGrade(input.getPotentialGrade())
            && (usesInferredPermutation(input.getPotentialOptions())
                || isNonFinite(result.getTrials().getBlackCubeTrials()));
    boolean suppressAdditional =
        hasGrade(input.getAdditionalGrade())
            && (usesInferredPermutation(input.getAdditionalOptions())
                || isNonFinite(result.getTrials().getAdditionalCubeTrials()));
    if (!suppressBlack && !suppressAdditional) {
      return result;
    }

    ComponentCosts costs =
        new ComponentCosts(
            suppressIfNeeded(suppressBlack, result.getCosts().getBlackCubeCost()),
            suppressIfNeeded(suppressAdditional, result.getCosts().getAdditionalCubeCost()),
            result.getCosts().getStarforceCost());
    ComponentTrials trials =
        new ComponentTrials(
            suppressIfNeeded(suppressBlack, result.getTrials().getBlackCubeTrials()),
            suppressIfNeeded(suppressAdditional, result.getTrials().getAdditionalCubeTrials()));
    return new ValuationResult(
        costs,
        trials,
        result.getEnhancePath(),
        result.getTableVersion(),
        result.getLogicVersion());
  }

  private boolean usesInferredPermutation(List<String> options) {
    DpInference inference = modeInferrer.infer(options);
    return !inference.isValid() || inference.getConfidence() < 0.5;
  }

  private static boolean hasGrade(String grade) {
    return grade != null && !grade.isEmpty();
  }

  private static boolean isNonFinite(Double value) {
    return value != null && !Double.isFinite(value);
  }

  private static Double suppressIfNeeded(boolean suppress, Double value) {
    return suppress ? Double.valueOf(0.0) : value;
  }

  private static List<String> immutableOptions(List<String> options) {
    return options == null ? List.of() : List.copyOf(options);
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }
}
