package maple.expectation.application.service.expectation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory;
import maple.expectation.application.service.flame.FlameInputResolver;
import maple.expectation.application.service.flame.FlameInputResolver.FlameInput;
import maple.expectation.application.service.starforce.NoljangProbabilityTable;
import maple.expectation.core.calculator.port.StarforceLookupPort;
import maple.expectation.core.domain.cost.CostFormatter;
import maple.expectation.core.domain.equipment.SecondaryWeaponCategory;
import maple.expectation.core.domain.flame.FlameEquipCategory;
import maple.expectation.core.domain.flame.FlameType;
import maple.expectation.core.flame.port.FlameTrialsPort;
import maple.expectation.core.probability.FlameScoreCalculator;
import maple.expectation.core.util.KahanSummation;
import maple.expectation.core.dto.cube.CubeCalculationInput;
import maple.expectation.core.dto.v4.EquipmentCalculationInput;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.CubeExpectationDto;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.FlameExpectationDto;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4.StarforceExpectationDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V4 프리셋 계산 헬퍼 (EquipmentExpectationServiceV4에서 분리)
 *
 * <h3>책임: 개별 프리셋 + 아이템 기대값 계산</h3>
 *
 * <ul>
 *   <li>프리셋 기대값 계산 (calculatePreset)
 *   <li>개별 아이템 빌드 (buildInput, buildItemResult)
 *   <li>스타포스/큐브 기대값 계산 (calculateStarforceExpectation, buildCubeExpectation)
 * </ul>
 *
 * <h3>성능 최적화 (2026-03-23)</h3>
 *
 * <ul>
 *   <li>BigDecimal → Double로 변경하여 계산 비용 절감
 *   <li>루프에서 Kahan Summation 사용으로 정확도 유지
 *   <li>모든 반환 타입을 Double로 통일
 * </ul>
 *
 * <h3>분해 근거</h3>
 *
 * <p>EquipmentExpectationServiceV4의 calculatePresetAsync() — 장비별 병렬 계산 (thenCombine)
 * Section 4)
 */
@Slf4j
@Component
public class PresetCalculationHelper {

  private final EquipmentExpectationCalculatorFactory calculatorFactory;
  private final StarforceLookupPort starforceLookupPort;
  private final FlameTrialsPort flameTrialsProvider;
  private final FlameInputResolver flameInputResolver;
  private final Executor itemExecutor;
  private final Semaphore globalItemPermits;

  public PresetCalculationHelper(
      EquipmentExpectationCalculatorFactory calculatorFactory,
      StarforceLookupPort starforceLookupPort,
      FlameTrialsPort flameTrialsProvider,
      FlameInputResolver flameInputResolver,
      @Qualifier("itemCalculationExecutor") Executor itemExecutor,
      @Value("${executor.item.max-pool-size:32}") int maxItemThreads,
      @Value("${executor.item.semaphore-permits:64}") int semaphorePermits) {
    this.calculatorFactory = calculatorFactory;
    this.starforceLookupPort = starforceLookupPort;
    this.flameTrialsProvider = flameTrialsProvider;
    this.flameInputResolver = flameInputResolver;
    this.itemExecutor = itemExecutor;
    this.globalItemPermits = new Semaphore(semaphorePermits);  // Gate concurrency; executor threads are the hard limit
  }

  /**
   * 프리셋 기대값 비동기 계산 — 장비별 병렬 처리 (thenCombine, join/get 없음)
   *
   * <p>각 장비의 계산이 서로 독립이므로 CompletableFuture.supplyAsync로 동시 시작,
   * thenCombine으로 결과를 누적하여 최종 PresetExpectation을 생성.
   *
   * <p>장비 계산은 itemCalculationExecutor에서 실행하여 presetCalculationExecutor와 격리.
   *
   * @param cubeInputs 프리셋의 큐브 입력 목록
   * @param presetNo 프리셋 번호 (1~3)
   * @param characterClass 직업명 (환생의 불꽃 동적 계산용)
   * @return 프리셋 기대값 CompletableFuture
   */
  public CompletableFuture<PresetExpectation> calculatePresetAsync(
      List<CubeCalculationInput> cubeInputs,
      int presetNo,
      String characterClass) {

    List<CompletableFuture<ItemExpectationV4>> itemFutures = new ArrayList<>();

    for (var cubeInput : cubeInputs) {
      if (!cubeInput.isReady()) {
        itemFutures.add(
            CompletableFuture.completedFuture(
                buildNoPotentialItem(cubeInput, presetNo, characterClass)));
        continue;
      }

      EquipmentCalculationInput input = buildInput(cubeInput, presetNo);

      itemFutures.add(
          CompletableFuture.supplyAsync(
                  () -> {
                    globalItemPermits.acquireUninterruptibly();
                    try {
                      return calculateSingleItem(input, cubeInput, characterClass);
                    } finally {
                      globalItemPermits.release();
                    }
                  },
                  itemExecutor));
    }

    if (itemFutures.isEmpty()) {
      return CompletableFuture.completedFuture(
          new PresetExpectation(
              presetNo, 0.0, CostFormatter.format(0.0), CostBreakdownDto.empty(), List.of()));
    }

    // thenCombine accumulation — no join/get
    CompletableFuture<List<ItemExpectationV4>> resultsFuture =
        CompletableFuture.completedFuture(new ArrayList<>());
    CompletableFuture<KahanSummation> costFuture =
        CompletableFuture.completedFuture(new KahanSummation());
    CompletableFuture<CostBreakdownDto> breakdownFuture =
        CompletableFuture.completedFuture(CostBreakdownDto.empty());

    for (CompletableFuture<ItemExpectationV4> itemFuture : itemFutures) {
      resultsFuture =
          resultsFuture.thenCombine(
              itemFuture,
              (list, item) -> {
                list.add(item);
                return list;
              });
      costFuture =
          costFuture.thenCombine(
              itemFuture.thenApply(ItemExpectationV4::getExpectedCost),
              (acc, cost) -> {
                acc.add(cost);
                return acc;
              });
      breakdownFuture =
          breakdownFuture.thenCombine(
              itemFuture.thenApply(ItemExpectationV4::getCostBreakdown),
              CostBreakdownDto::add);
    }

    return resultsFuture
        .thenCombine(costFuture, (results, costAcc) -> new PresetAggregation(results, costAcc))
        .thenCombine(breakdownFuture, (agg, breakdown) -> agg.withBreakdown(breakdown))
        .thenApply(
            agg -> {
              double totalCost = agg.costAcc.sum();
              return new PresetExpectation(
                  presetNo,
                  totalCost,
                  CostFormatter.format(totalCost),
                  agg.breakdown,
                  agg.results);
            });
  }

  /** Accumulation helper for async preset calculation */
  private record PresetAggregation(
      List<ItemExpectationV4> results, KahanSummation costAcc, CostBreakdownDto breakdown) {
    PresetAggregation(List<ItemExpectationV4> results, KahanSummation costAcc) {
      this(results, costAcc, CostBreakdownDto.empty());
    }

    PresetAggregation withBreakdown(CostBreakdownDto breakdown) {
      return new PresetAggregation(results, costAcc, breakdown);
    }
  }

  /** 큐브 입력 → 계산 입력 변환 */
  EquipmentCalculationInput buildInput(CubeCalculationInput cubeInput, int presetNo) {
    boolean isNoljang = cubeInput.isNoljangEquipment();
    int parsedStarforce = cubeInput.getStarforce();
    int targetStar =
        isNoljang
            ? Math.min(parsedStarforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
            : parsedStarforce;

    String potentialPart =
        SecondaryWeaponCategory.resolvePotentialPart(
            cubeInput.getPart(), cubeInput.getItemEquipmentPart());

    return EquipmentCalculationInput.builder()
        .itemName(cubeInput.getItemName())
        .itemPart(potentialPart)
        .itemEquipmentPart(cubeInput.getItemEquipmentPart())
        .itemIcon(cubeInput.getItemIcon())
        .itemLevel(cubeInput.getLevel())
        .presetNo(presetNo)
        .isNoljang(isNoljang)
        .potentialGrade(cubeInput.getGrade())
        .potentialOptions(cubeInput.getOptions())
        .additionalPotentialGrade(cubeInput.getAdditionalGrade())
        .additionalPotentialOptions(cubeInput.getAdditionalOptions())
        .currentStar(0)
        .targetStar(targetStar)
        .build();
  }

  /** 단일 아이템 기대값 계산 */
  private ItemExpectationV4 calculateSingleItem(
      EquipmentCalculationInput input, CubeCalculationInput cubeInput, String characterClass) {
    EquipmentExpectationCalculator calculator = calculatorFactory.createFullCalculator(input);
    double itemCost = calculator.calculateCost();
    var costBreakdown = calculator.getDetailedCosts();

    return buildItemResult(
        input, cubeInput, itemCost, costBreakdown, calculator.getEnhancePath(), characterClass);
  }

  /** 아이템 기대값 결과 빌드 */
  private ItemExpectationV4 buildItemResult(
      EquipmentCalculationInput input,
      CubeCalculationInput cubeInput,
      double itemCost,
      EquipmentExpectationCalculator.CostBreakdown costBreakdown,
      String enhancePath,
      String characterClass) {
    StarforceExpectationDto starforceExpectation =
        calculateStarforceExpectation(
            input.getCurrentStar(), input.getTargetStar(), input.getItemLevel(), input.isNoljang());

    FlameExpectationDto flameExpectation =
        resolveFlameExpectation(cubeInput, characterClass, input.getItemLevel());

    String potentialText = formatPotentialOptions(input.getPotentialOptions());
    String additionalPotentialText = formatPotentialOptions(input.getAdditionalPotentialOptions());

    CubeExpectationDto blackCubeExpectation =
        buildCubeExpectation(
            costBreakdown.blackCubeCost(),
            costBreakdown.blackCubeTrials(),
            input.getPotentialGrade(),
            "LEGENDARY",
            potentialText);
    CubeExpectationDto additionalCubeExpectation =
        buildCubeExpectation(
            costBreakdown.additionalCubeCost(),
            costBreakdown.additionalCubeTrials(),
            input.getAdditionalPotentialGrade(),
            "LEGENDARY",
            additionalPotentialText);

    return ItemExpectationV4.builder()
        .itemName(input.getItemName())
        .itemIcon(input.getItemIcon())
        .itemPart(input.getItemPart())
        .itemLevel(input.getItemLevel())
        .expectedCost(itemCost)
        .expectedCostText(CostFormatter.format(itemCost))
        .costBreakdown(toCostBreakdownDto(costBreakdown))
        .enhancePath(enhancePath)
        .potentialGrade(input.getPotentialGrade())
        .additionalPotentialGrade(input.getAdditionalPotentialGrade())
        .currentStar(input.getCurrentStar())
        .targetStar(input.getTargetStar())
        .isNoljang(input.isNoljang())
        .specialRingLevel(cubeInput.getSpecialRingLevel())
        .blackCubeExpectation(blackCubeExpectation)
        .additionalCubeExpectation(additionalCubeExpectation)
        .starforceExpectation(starforceExpectation)
        .flameExpectation(flameExpectation)
        .build();
  }

  /** 잠재능력 없는 아이템 빌드 */
  ItemExpectationV4 buildNoPotentialItem(
      CubeCalculationInput cubeInput, int presetNo, String characterClass) {
    FlameExpectationDto flameExpectation =
        resolveFlameExpectation(cubeInput, characterClass, cubeInput.getLevel());

    return ItemExpectationV4.builder()
        .itemName(cubeInput.getItemName())
        .itemIcon(cubeInput.getItemIcon())
        .itemPart(cubeInput.getPart())
        .itemLevel(cubeInput.getLevel())
        .expectedCost(0.0)
        .expectedCostText("0원")
        .costBreakdown(CostBreakdownDto.empty())
        .enhancePath("")
        .potentialGrade(null)
        .additionalPotentialGrade(null)
        .currentStar(0)
        .targetStar(cubeInput.getStarforce())
        .isNoljang(cubeInput.isNoljangEquipment())
        .specialRingLevel(cubeInput.getSpecialRingLevel())
        .blackCubeExpectation(CubeExpectationDto.empty())
        .additionalCubeExpectation(CubeExpectationDto.empty())
        .starforceExpectation(StarforceExpectationDto.empty())
        .flameExpectation(flameExpectation)
        .build();
  }

  /** 큐브 기대값 DTO 빌드 */
  CubeExpectationDto buildCubeExpectation(
      double cost, double trials, String currentGrade, String targetGrade, String potentialText) {
    if (cost == 0.0) {
      return CubeExpectationDto.empty();
    }

    return CubeExpectationDto.builder()
        .expectedCost(cost)
        .expectedCostText(CostFormatter.format(cost))
        .expectedTrials(trials)
        .currentGrade(currentGrade)
        .targetGrade(targetGrade)
        .potential(potentialText)
        .build();
  }

  /** 스타포스 기대값 계산 */
  StarforceExpectationDto calculateStarforceExpectation(
      int currentStar, int targetStar, int itemLevel, boolean isNoljang) {
    if (isNoljang) {
      return calculateNoljangStarforce(currentStar, targetStar, itemLevel);
    }
    return calculateRegularStarforce(currentStar, targetStar, itemLevel);
  }

  private StarforceExpectationDto calculateNoljangStarforce(
      int currentStar, int targetStar, int itemLevel) {
    double noljangCost =
        NoljangProbabilityTable.getExpectedCostFromStar(
            currentStar, targetStar, itemLevel, true, true);
    double roundedCost = roundToNearest100(noljangCost);
    return StarforceExpectationDto.builder()
        .currentStar(currentStar)
        .targetStar(targetStar)
        .isNoljang(true)
        .costWithoutDestroyPrevention(roundedCost)
        .costWithoutDestroyPreventionText(CostFormatter.format(roundedCost))
        .expectedDestroyCountWithout(0.0)
        .costWithDestroyPrevention(roundedCost)
        .costWithDestroyPreventionText(CostFormatter.format(roundedCost))
        .expectedDestroyCountWith(0.0)
        .build();
  }

  private StarforceExpectationDto calculateRegularStarforce(
      int currentStar, int targetStar, int itemLevel) {
    double costWithout =
        starforceLookupPort.getExpectedCost(
            currentStar, targetStar, itemLevel, true, true, true, false);
    double destroyCountWithout =
        starforceLookupPort.getExpectedDestroyCount(currentStar, targetStar, true, true, false);

    double costWith =
        starforceLookupPort.getExpectedCost(
            currentStar, targetStar, itemLevel, true, true, true, true);
    double destroyCountWith =
        starforceLookupPort.getExpectedDestroyCount(currentStar, targetStar, true, true, true);

    double roundedCostWithout = roundToNearest100(costWithout);
    double roundedCostWith = roundToNearest100(costWith);

    return StarforceExpectationDto.builder()
        .currentStar(currentStar)
        .targetStar(targetStar)
        .isNoljang(false)
        .costWithoutDestroyPrevention(roundedCostWithout)
        .costWithoutDestroyPreventionText(CostFormatter.format(roundedCostWithout))
        .expectedDestroyCountWithout(destroyCountWithout)
        .costWithDestroyPrevention(roundedCostWith)
        .costWithDestroyPreventionText(CostFormatter.format(roundedCostWith))
        .expectedDestroyCountWith(destroyCountWith)
        .build();
  }

  /** 잠재능력 옵션 포맷팅 */
  String formatPotentialOptions(List<String> options) {
    if (options == null || options.isEmpty()) {
      return "";
    }
    return String.join(" | ", options);
  }

  /**
   * 불꽃 기대값 해석 및 계산 (#303 동적 불꽃 계산)
   *
   * <p>FlameInputResolver로 장비 데이터에서 동적으로 계산 입력을 추출하고, 추옵이 없는 장비는 empty를 반환합니다.
   */
  private FlameExpectationDto resolveFlameExpectation(
      CubeCalculationInput cubeInput, String characterClass, int level) {
    FlameInput flameInput = flameInputResolver.resolve(cubeInput, characterClass);
    if (flameInput == null) {
      return FlameExpectationDto.empty();
    }
    return calculateFlameExpectation(level, flameInput);
  }

  /**
   * 환생의 불꽃 기대값 계산
   *
   * <p>강력한/영원한/심연의 불꽃 3종에 대해 동일 목표 환산치로 기대 시도 횟수를 계산합니다.
   */
  private FlameExpectationDto calculateFlameExpectation(int level, FlameInput flameInput) {
    FlameEquipCategory category =
        FlameEquipCategory.of(flameInput.isBossDrop(), flameInput.isWeapon());
    FlameScoreCalculator.JobWeights weights = flameInput.weights();
    int target = flameInput.target();
    int baseAtt = flameInput.baseAtt();
    int baseMag = flameInput.baseMag();

    double powerful =
        calculateFlameTrials(
            category, FlameType.POWERFUL, level, weights, target, baseAtt, baseMag);
    double eternal =
        calculateFlameTrials(category, FlameType.ETERNAL, level, weights, target, baseAtt, baseMag);
    double abyss =
        calculateFlameTrials(category, FlameType.ABYSS, level, weights, target, baseAtt, baseMag);

    return FlameExpectationDto.builder()
        .powerfulFlameTrials(powerful)
        .eternalFlameTrials(eternal)
        .abyssFlameTrials(abyss)
        .build();
  }

  private double calculateFlameTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag) {
    Double trials =
        flameTrialsProvider.calculateExpectedTrials(
            category, flameType, level, weights, target, baseAtt, baseMag);

    if (trials == null || !Double.isFinite(trials)) {
      return 0.0;
    }
    return Math.round(trials * 100.0) / 100.0; // 소수점 2자리로 반올림
  }

  /** 100원 단위 반올림 */
  double roundToNearest100(double value) {
    return Math.round(value / 100.0) * 100.0;
  }

  /** CostBreakdown → CostBreakdownDto 타입 안전 변환 (#630) */
  private static CostBreakdownDto toCostBreakdownDto(
      EquipmentExpectationCalculator.CostBreakdown cb) {
    return new CostBreakdownDto(
        cb.blackCubeCost(), cb.redCubeCost(), cb.additionalCubeCost(), cb.starforceCost());
  }
}
